"""Joiner Lambda — publishes enriched events when correlation records are complete."""

import json
import logging
import os

import boto3
from boto3.dynamodb.types import TypeDeserializer

from log_util import log_structured
from metadata_copy import copy_metadata_from_master, fan_out_metadata_to_per_file_records
from orphan_detection import detect_orphan
from publish import publish_enriched_event, validate_metadata

logger = logging.getLogger()
logger.setLevel(logging.INFO)

EVENT_BUS_NAME = os.environ.get("EVENT_BUS_NAME", "sftp-connector-helper-bus")
TABLE_NAME = os.environ.get("TABLE_NAME", "sftp-connector-helper")
SNS_TOPIC_ARN = os.environ.get("SNS_TOPIC_ARN", "")

cloudwatch = boto3.client("cloudwatch")
events = boto3.client("events")
sns = boto3.client("sns")
dynamodb = boto3.resource("dynamodb")
table = dynamodb.Table(TABLE_NAME)

deserializer = TypeDeserializer()


def unmarshal(dynamodb_item: dict) -> dict:
    """Convert DynamoDB typed attributes to Python dict."""
    return {k: deserializer.deserialize(v) for k, v in dynamodb_item.items()}


def _has_both_fields(image: dict | None) -> bool:
    """Check if a DynamoDB image has both metadata and eventResult."""
    if image is None:
        return False
    return "metadata" in image and "eventResult" in image


def _extract_connector_id(event_result_str: str) -> str:
    """Extract connector-id from stored event, with fallback."""
    try:
        parsed = json.loads(event_result_str)
        return parsed.get("detail", {}).get("connector-id", "unknown")
    except (json.JSONDecodeError, TypeError, AttributeError):
        return "unknown"


def _emit_invalid_metadata_metric(connector_id: str) -> None:
    """Emit InvalidMetadata metric with and without ConnectorId dimension."""
    try:
        cloudwatch.put_metric_data(
            Namespace="SftpConnectorHelper",
            MetricData=[
                {
                    "MetricName": "InvalidMetadata",
                    "Dimensions": [{"Name": "ConnectorId", "Value": connector_id}],
                    "Value": 1,
                    "Unit": "Count",
                },
                {
                    "MetricName": "InvalidMetadata",
                    "Value": 1,
                    "Unit": "Count",
                },
            ],
        )
    except Exception:
        log_structured(
            "WARNING",
            "Failed to emit InvalidMetadata metric",
            connector_id=connector_id,
        )


def _metadata_copy_dispatch(new_image: dict, job_id: str) -> bool:
    """Dispatch metadata-copy logic. Returns True if copy path was taken."""
    has_metadata = "metadata" in new_image
    has_event_result = "eventResult" in new_image
    has_transfer_id = "transferId" in new_image

    # Per-file detection: has eventResult + transferId, no metadata
    if has_event_result and has_transfer_id and not has_metadata:
        transfer_id = new_image["transferId"]
        log_structured("INFO", "Per-file record detected, copying metadata from master", job_id=job_id, transfer_id=transfer_id)
        copy_metadata_from_master(table, transfer_id, job_id)
        return True

    # Master detection: has metadata, no eventResult, no transferId
    if has_metadata and not has_event_result and not has_transfer_id:
        log_structured("INFO", "Master record detected, fanning out metadata", job_id=job_id)
        fan_out_metadata_to_per_file_records(table, job_id, new_image["metadata"])
        return True

    return False


def _process_record(stream_record: dict) -> None:
    """Process a single DynamoDB Stream record."""
    event_name = stream_record.get("eventName")
    dynamodb_data = stream_record.get("dynamodb", {})
    job_id = dynamodb_data.get("Keys", {}).get("jobId", {}).get("S", "unknown")

    if event_name == "REMOVE":
        old_image_raw = dynamodb_data.get("OldImage")
        if old_image_raw:
            old_image = unmarshal(old_image_raw)
            detect_orphan(table, old_image, job_id, sns, SNS_TOPIC_ARN, cloudwatch)
        return

    if event_name not in ("MODIFY", "INSERT"):
        log_structured(
            "WARNING",
            "Unknown eventName, skipping",
            job_id=job_id,
            event_name=event_name,
        )
        return

    new_image_raw = dynamodb_data.get("NewImage")
    if not new_image_raw:
        return

    new_image = unmarshal(new_image_raw)

    # Loop prevention: on MODIFY, skip if OldImage already had both fields
    if event_name == "MODIFY":
        old_image_raw = dynamodb_data.get("OldImage")
        if old_image_raw:
            old_image = unmarshal(old_image_raw)
            if _has_both_fields(old_image):
                log_structured(
                    "INFO",
                    "Skipping already-published record",
                    job_id=job_id,
                )
                return
            # Metadata-copy loop prevention: OldImage already has metadata → skip copy rules
            if "metadata" not in old_image:
                if _metadata_copy_dispatch(new_image, job_id):
                    return
        else:
            if _metadata_copy_dispatch(new_image, job_id):
                return
    elif event_name == "INSERT":
        if _metadata_copy_dispatch(new_image, job_id):
            return

    # Completeness check
    if not _has_both_fields(new_image):
        return

    metadata_str = new_image["metadata"]
    event_result_str = new_image["eventResult"]
    connector_id = _extract_connector_id(event_result_str)

    # Metadata validation
    if not validate_metadata(metadata_str):
        log_structured(
            "ERROR",
            "Invalid metadata, not a JSON object",
            job_id=job_id,
            connector_id=connector_id,
            metadata_preview=metadata_str[:200],
        )
        _emit_invalid_metadata_metric(connector_id)
        return

    # Publish enriched event
    log_structured(
        "INFO",
        "Record complete, publishing enriched event",
        job_id=job_id,
        connector_id=connector_id,
    )
    publish_enriched_event(events, EVENT_BUS_NAME, event_result_str, metadata_str, job_id)


def lambda_handler(event, context):
    """Process SQS records containing DynamoDB Stream records."""
    batch_item_failures = []

    for record in event.get("Records", []):
        message_id = record.get("messageId", "")
        try:
            stream_record = json.loads(record["body"])
            _process_record(stream_record)
        except Exception:
            log_structured(
                "ERROR",
                "Failed to process record",
                message_id=message_id,
            )
            batch_item_failures.append({"itemIdentifier": message_id})

    return {"batchItemFailures": batch_item_failures}
