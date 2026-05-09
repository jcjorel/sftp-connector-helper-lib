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
    2. Master record (metadata, no eventResult, no transferId) → query GSI to check per-file records
    3. metadata only (non-master, no eventResult) → orphan
    4. eventResult only (no metadata) → orphan

    Master record detection: master records have metadata + no eventResult + no transferId attribute.
    Per-file records always have a transferId attribute pointing to their master.
    """
    has_metadata = "metadata" in old_image
    has_event_result = "eventResult" in old_image
    has_transfer_id = "transferId" in old_image

    # Branch 1: normal expiry
    if has_metadata and has_event_result:
        log_structured("INFO", "Normal expiry, both fields present", job_id=job_id)
        return

    # Branch 2: master record (metadata, no eventResult, no transferId)
    if has_metadata and not has_event_result and not has_transfer_id:
        response = table.query(
            IndexName="transferId-index",
            KeyConditionExpression=Key("transferId").eq(job_id),
            Limit=1,
        )
        if response.get("Count", 0) > 0:
            log_structured("INFO", "Master record normal expiry, per-file records exist", job_id=job_id)
            return
        # No per-file records found — could be a non-fan-out operation or an orphaned master
        connector_id = "UNKNOWN"
        log_structured("WARNING", "Orphaned record, metadata without event", job_id=job_id, connector_id=connector_id, operation="orphan_detection")
        try:
            _emit_orphan_metric(cloudwatch_client, connector_id)
            _publish_orphan(sns_client, sns_topic_arn, job_id, "metadata-only", connector_id, old_image)
        except Exception:
            log_structured("WARNING", "Failed to emit orphan observability", job_id=job_id, operation="orphan_detection")
        return

    # Branch 3: metadata-only orphan (per-file record that never got joined)
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
