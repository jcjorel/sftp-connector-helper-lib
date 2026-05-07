"""Orphan detection for expired DynamoDB TTL records."""

import json
from decimal import Decimal

from boto3.dynamodb.conditions import Key

from log_util import log_structured


class _DecimalEncoder(json.JSONEncoder):
    """JSON encoder that handles Decimal from DynamoDB."""

    def default(self, o):
        if isinstance(o, Decimal):
            return int(o) if o == int(o) else float(o)
        return super().default(o)


def _extract_connector_id(old_image: dict) -> str:
    """Extract connector-id from eventResult in old_image, fallback UNKNOWN."""
    event_result_str = old_image.get("eventResult")
    if not event_result_str:
        return "UNKNOWN"
    try:
        parsed = json.loads(event_result_str)
        return parsed.get("detail", {}).get("connector-id", "UNKNOWN")
    except (json.JSONDecodeError, TypeError, AttributeError):
        return "UNKNOWN"


def _emit_orphan_metric(cloudwatch_client, connector_id: str) -> None:
    """Emit OrphanedRecords metric with and without ConnectorId dimension."""
    cloudwatch_client.put_metric_data(
        Namespace="SftpConnectorHelper",
        MetricData=[
            {
                "MetricName": "OrphanedRecords",
                "Dimensions": [{"Name": "ConnectorId", "Value": connector_id}],
                "Value": 1,
                "Unit": "Count",
            },
            {
                "MetricName": "OrphanedRecords",
                "Value": 1,
                "Unit": "Count",
            },
        ],
    )


def _publish_orphan(sns_client, sns_topic_arn: str, job_id: str, orphan_type: str, connector_id: str, old_image: dict) -> None:
    """Publish orphan details to SNS topic."""
    if not sns_topic_arn:
        return
    sns_client.publish(
        TopicArn=sns_topic_arn,
        Subject=f"Orphaned Record Detected: {job_id}",
        Message=json.dumps({
            "job_id": job_id,
            "orphan_type": orphan_type,
            "connector_id": connector_id,
            "old_image": old_image,
        }, cls=_DecimalEncoder),
    )


def detect_orphan(table, old_image: dict, job_id: str, sns_client, sns_topic_arn: str, cloudwatch_client) -> None:
    """Detect orphaned records from TTL-expired DynamoDB items.

    Four branches:
    1. Both metadata AND eventResult → normal expiry, ignore
    2. operationType == FILE_TRANSFER (master) → query GSI Limit=1
    3. metadata only (no eventResult) → orphan
    4. eventResult only (no metadata) → orphan
    """
    has_metadata = "metadata" in old_image
    has_event_result = "eventResult" in old_image

    # Branch 1: normal expiry
    if has_metadata and has_event_result:
        log_structured("INFO", "Normal expiry, both fields present", job_id=job_id)
        return

    # Branch 2: FILE_TRANSFER master record
    if old_image.get("operationType") == "FILE_TRANSFER":
        response = table.query(
            IndexName="transferId-index",
            KeyConditionExpression=Key("transferId").eq(job_id),
            Limit=1,
        )
        if response.get("Count", 0) == 0:
            connector_id = _extract_connector_id(old_image)
            log_structured("WARNING", "Orphaned master record, no per-file records found", job_id=job_id, connector_id=connector_id, operation="orphan_detection")
            try:
                _emit_orphan_metric(cloudwatch_client, connector_id)
                _publish_orphan(sns_client, sns_topic_arn, job_id, "master-no-files", connector_id, old_image)
            except Exception:
                log_structured("WARNING", "Failed to emit orphan observability", job_id=job_id, operation="orphan_detection")
        else:
            log_structured("INFO", "Master record normal expiry, per-file records exist", job_id=job_id)
        return

    # Branch 3: metadata-only orphan
    if has_metadata and not has_event_result:
        connector_id = "UNKNOWN"
        log_structured("WARNING", "Orphaned record, metadata without event", job_id=job_id, connector_id=connector_id, operation="orphan_detection")
        try:
            _emit_orphan_metric(cloudwatch_client, connector_id)
            _publish_orphan(sns_client, sns_topic_arn, job_id, "metadata-only", connector_id, old_image)
        except Exception:
            log_structured("WARNING", "Failed to emit orphan observability", job_id=job_id, operation="orphan_detection")
        return

    # Branch 4: event-only orphan
    if has_event_result and not has_metadata:
        connector_id = _extract_connector_id(old_image)
        log_structured("WARNING", "Orphaned record, event without metadata", job_id=job_id, connector_id=connector_id, operation="orphan_detection")
        try:
            _emit_orphan_metric(cloudwatch_client, connector_id)
            _publish_orphan(sns_client, sns_topic_arn, job_id, "event-only", connector_id, old_image)
        except Exception:
            log_structured("WARNING", "Failed to emit orphan observability", job_id=job_id, operation="orphan_detection")
        return
