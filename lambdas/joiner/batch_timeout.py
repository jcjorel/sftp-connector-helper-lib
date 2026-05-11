"""Batch timeout scheduling, hop/fire processing, and event assembly."""

import json
import time

from log_util import log_structured
from publish import publish_batch_completion_event


def schedule_batch_timeout(table, sqs_client, queue_url: str, master_image: dict) -> None:
    """Send delayed SQS message to trigger timeout check. Idempotent via conditional write."""
    job_id = master_image["jobId"]

    # Pre-check: skip if already scheduled
    resp = table.get_item(
        Key={"jobId": job_id},
        ProjectionExpression="batchTimeoutScheduled",
        ConsistentRead=True,
    )
    if resp.get("Item", {}).get("batchTimeoutScheduled"):
        return

    target_time = int(master_image["batchTimeoutAt"])
    remaining = max(target_time - int(time.time()), 1)
    delay = min(remaining, 900)

    file_paths = master_image.get("filePaths", [])
    if isinstance(file_paths, set):
        file_paths = list(file_paths)

    sqs_client.send_message(
        QueueUrl=queue_url,
        MessageBody=json.dumps({
            "transferId": job_id,
            "targetTimeoutAt": target_time,
            "connectorId": master_image.get("connectorId", "unknown"),
            "expectedFiles": int(master_image["expectedFiles"]),
            "transferDirection": master_image.get("transferDirection", "SEND"),
            "metadata": master_image["metadata"],
            "filePaths": file_paths,
        }),
        DelaySeconds=delay,
    )

    # Dedup marker AFTER successful SQS send
    try:
        table.update_item(
            Key={"jobId": job_id},
            UpdateExpression="SET batchTimeoutScheduled = :t",
            ConditionExpression="attribute_not_exists(batchTimeoutScheduled)",
            ExpressionAttributeValues={":t": True},
        )
    except table.meta.client.exceptions.ConditionalCheckFailedException:
        pass  # Harmless duplicate


def process_timeout_message(table, sqs_client, queue_url: str, events_client, bus_name: str, body: dict) -> None:
    """Process a timeout message: hop or fire."""
    transfer_id = body["transferId"]
    target_time = body["targetTimeoutAt"]
    now = int(time.time())

    # Hop: not yet time
    if now < target_time:
        remaining = min(target_time - now, 900)
        sqs_client.send_message(
            QueueUrl=queue_url,
            MessageBody=json.dumps(body),
            DelaySeconds=remaining,
        )
        return

    # Fire time — single GetItem by PK
    resp = table.get_item(
        Key={"jobId": transfer_id},
        ProjectionExpression="resolvedCount, batchEventPublished, batchTimeoutPublished, fileStatuses",
        ConsistentRead=True,
    )
    item = resp.get("Item")
    if not item:
        # Record TTL'd — publish degraded event from message body
        detail, detail_type = _assemble_timeout_event(body, {"fileStatuses": {}})
        publish_batch_completion_event(events_client, bus_name, detail, detail_type)
        log_structured("WARN", "Published degraded batch timeout event (record TTL'd)",
                       transfer_id=transfer_id, expected_files=body["expectedFiles"])
        return

    # Guard: already handled?
    if item.get("batchEventPublished") or item.get("batchTimeoutPublished"):
        return

    # Guard: all files resolved?
    resolved = int(item.get("resolvedCount", 0))
    expected = body["expectedFiles"]
    if resolved >= expected:
        return

    # Publish timeout event FIRST (at-least-once)
    detail, detail_type = _assemble_timeout_event(body, item)
    publish_batch_completion_event(events_client, bus_name, detail, detail_type)

    # Dedup marker AFTER publish — catch ALL exceptions to avoid re-raise causing duplicate on retry
    try:
        table.update_item(
            Key={"jobId": transfer_id},
            UpdateExpression="SET batchTimeoutPublished = :t",
            ConditionExpression="attribute_not_exists(batchTimeoutPublished) AND resolvedCount < :expected",
            ExpressionAttributeValues={":t": True, ":expected": expected},
        )
    except table.meta.client.exceptions.ConditionalCheckFailedException:
        pass  # Event already published — at-least-once acceptable
    except Exception as e:
        log_structured("ERROR", "Failed to write batchTimeoutPublished marker (event already published)",
                       transfer_id=transfer_id, exception_type=type(e).__name__)

    log_structured("INFO", "Published batch timeout event",
                   transfer_id=transfer_id, resolved_count=resolved,
                   expected_files=expected, timed_out_count=expected - resolved)


def _assemble_timeout_event(msg: dict, item: dict) -> tuple:
    """Assemble timeout event detail from SQS message + DDB guard read."""
    try:
        metadata = json.loads(msg["metadata"])
    except (json.JSONDecodeError, TypeError, KeyError):
        log_structured("ERROR", "Invalid metadata JSON in timeout message, using raw fallback",
                       transfer_id=msg.get("transferId", "unknown"))
        metadata = {"_raw_metadata": msg.get("metadata", "")}

    file_statuses = item.get("fileStatuses", {})
    all_file_paths = msg.get("filePaths", [])

    resolved_files = []
    resolved_paths = set()
    for key, entry in file_statuses.items():
        if key == "_init" or not isinstance(entry, dict):
            continue
        if not entry.get("fileTransferId"):
            continue
        try:
            detail = json.loads(entry.get("eventDetail", "{}"))
        except (json.JSONDecodeError, TypeError):
            log_structured("WARN", "Invalid eventDetail JSON in fileStatuses entry, skipping",
                           transfer_id=msg.get("transferId", "unknown"), file_transfer_id=entry.get("fileTransferId"))
            continue
        detail["_helper_metadata"] = metadata
        resolved_files.append(detail)
        resolved_paths.add(entry.get("filePath", ""))

    # Placeholder entries for timed-out files
    for path in all_file_paths:
        if path not in resolved_paths:
            resolved_files.append({
                "file-transfer-id": "unknown",
                "status-code": "TIMED_OUT",
                "file-path": path,
                "_helper_metadata": metadata,
            })

    completed = sum(1 for f in resolved_files if f.get("status-code") == "COMPLETED")
    failed = sum(1 for f in resolved_files if f.get("status-code") not in ("COMPLETED", "TIMED_OUT"))
    timed_out = sum(1 for f in resolved_files if f.get("status-code") == "TIMED_OUT")

    direction = msg.get("transferDirection", "SEND")
    detail_type = (
        "SFTP Connector Whole File Send Transfer Timed Out - CUSTOM" if direction == "SEND"
        else "SFTP Connector Whole File Retrieve Transfer Timed Out - CUSTOM"
    )

    return {
        "transfer-id": msg["transferId"],
        "connector-id": msg["connectorId"],
        "status-code": "TIMED_OUT",
        "file-count": msg["expectedFiles"],
        "completed-count": completed,
        "failed-count": failed,
        "timed-out-count": timed_out,
        "_helper_metadata": metadata,
        "files": resolved_files,
    }, detail_type
