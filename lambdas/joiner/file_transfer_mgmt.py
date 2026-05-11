"""Whole Transfer Completion — batch tracking, emission mode gating, event assembly."""

import hashlib
import json

from log_util import log_structured


def _hash_key(file_transfer_id: str) -> str:
    """SHA-256 hash of file-transfer-id for use as DynamoDB map key."""
    return hashlib.sha256(file_transfer_id.encode()).hexdigest()
from publish import publish_batch_completion_event


def should_publish_individual_event(master_record: dict | None) -> bool:
    """Return True if individual enriched events should be published.

    Missing emissionMode (legacy records) → publish individual events.
    """
    if master_record is None:
        return True
    mode = master_record.get("emissionMode")
    if mode is None:
        return True
    return mode != "WHOLE_TRANSFER_COMPLETION_ONLY"


def _derive_batch_status(file_statuses: dict) -> str:
    """Derive status-code from file statuses map."""
    statuses = {v.get("status") for v in file_statuses.values()}
    if statuses == {"COMPLETED"}:
        return "ALL_COMPLETED"
    if statuses == {"FAILED"}:
        return "ALL_FAILED"
    return "PARTIAL_FAILURE"


def _derive_detail_type(transfer_direction: str) -> str:
    """Derive batch event detail-type from transfer direction."""
    if transfer_direction == "SEND":
        return "SFTP Connector Whole File Send Transfer Completed - CUSTOM"
    return "SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM"


def _assemble_batch_event_detail(master_record: dict, file_statuses: dict) -> dict:
    """Assemble the batch completion event detail payload."""
    metadata = json.loads(master_record.get("metadata", "{}"))
    transfer_id = master_record["jobId"]
    direction = master_record.get("transferDirection", "SEND")

    # Filter out "_init" sentinel key used to force DynamoDB map persistence
    real_statuses = {k: v for k, v in file_statuses.items() if k != "_init"}

    files = []
    for ft_id, entry in real_statuses.items():
        file_detail = json.loads(entry.get("eventDetail", "{}"))
        file_detail["_helper_metadata"] = metadata
        files.append(file_detail)

    completed = sum(1 for f in files if f.get("status-code") == "COMPLETED")
    failed = len(files) - completed

    return {
        "transfer-id": transfer_id,
        "connector-id": master_record.get("connectorId", "unknown"),
        "status-code": _derive_batch_status(real_statuses),
        "file-count": len(files),
        "completed-count": completed,
        "failed-count": failed,
        "_helper_metadata": metadata,
        "files": files,
    }, _derive_detail_type(direction)


def _check_and_publish_batch(
    table, events_client, bus_name: str, master_item: dict, resolved_count, expected_files, batch_published
) -> None:
    """Step 4 guard: publish batch event if all files resolved and not yet published.

    Uses conditional write for mutual exclusion with timeout path.
    """
    mode = master_item.get("emissionMode", "INDIVIDUAL_FILE_EVENTS_ONLY")
    if mode == "INDIVIDUAL_FILE_EVENTS_ONLY":
        return

    if resolved_count != expected_files:
        return
    if batch_published:
        return

    # Mutual exclusion via conditional write — prevents race with timeout path
    try:
        table.update_item(
            Key={"jobId": master_item["jobId"]},
            UpdateExpression="SET batchEventPublished = :t",
            ConditionExpression="attribute_not_exists(batchEventPublished) AND attribute_not_exists(batchTimeoutPublished)",
            ExpressionAttributeValues={":t": True},
        )
    except table.meta.client.exceptions.ConditionalCheckFailedException:
        return  # Timeout event already fired, or duplicate

    # Conditional write succeeded — safe to publish
    file_statuses = master_item.get("fileStatuses", {})
    detail, detail_type = _assemble_batch_event_detail(master_item, file_statuses)

    log_structured("INFO", "Publishing batch completion event",
                   transfer_id=master_item["jobId"],
                   file_count=len(file_statuses),
                   batch_status=detail["status-code"])

    publish_batch_completion_event(events_client, bus_name, detail, detail_type)


def process_file_completion(
    table, events_client, bus_name: str, master_record: dict, file_transfer_id: str, enriched_event_detail: dict
) -> None:
    """Track per-file completion in master record and publish batch event if ready.

    Steps 3-4 from the spec: conditional update + batch guard.
    """
    expected_files = master_record.get("expectedFiles")
    if expected_files is None:
        return  # Not a batch-tracked transfer

    status = enriched_event_detail.get("status-code", "COMPLETED")
    file_path = enriched_event_detail.get("file-path", "")

    file_entry = {
        "fileTransferId": file_transfer_id,
        "status": status,
        "filePath": file_path,
        "eventDetail": json.dumps(enriched_event_detail),
    }

    hashed_key = _hash_key(file_transfer_id)

    try:
        response = table.update_item(
            Key={"jobId": master_record["jobId"]},
            UpdateExpression="SET #fs.#ftId = :entry ADD resolvedCount :one",
            ExpressionAttributeNames={"#fs": "fileStatuses", "#ftId": hashed_key},
            ExpressionAttributeValues={":entry": file_entry, ":one": 1},
            ConditionExpression="attribute_not_exists(#fs.#ftId)",
            ReturnValues="ALL_NEW",
        )
        updated = response.get("Attributes", {})
        resolved_count = int(updated.get("resolvedCount", 0))
        batch_published = updated.get("batchEventPublished", False)
    except table.meta.client.exceptions.ConditionalCheckFailedException:
        log_structured("INFO", "Duplicate file-transfer-id, skipping increment",
                       transfer_id=master_record["jobId"], file_transfer_id=file_transfer_id)
        # Re-read for step 4 guard evaluation
        resp = table.get_item(Key={"jobId": master_record["jobId"]}, ConsistentRead=True)
        updated = resp.get("Item", {})
        resolved_count = int(updated.get("resolvedCount", 0))
        batch_published = updated.get("batchEventPublished", False)

    _check_and_publish_batch(table, events_client, bus_name, updated, resolved_count, expected_files, batch_published)


def reconcile_existing_files(
    table, events_client, bus_name: str, master_job_id: str, master_record: dict, per_file_items: list
) -> None:
    """Fan-out reconciliation: update batch state for per-file records that arrived before master.

    Called from metadata_copy.fan_out_metadata_to_per_file_records when master has emissionMode.
    """
    expected_files = master_record.get("expectedFiles")
    if expected_files is None:
        return

    mode = master_record.get("emissionMode", "INDIVIDUAL_FILE_EVENTS_ONLY")

    for item in per_file_items:
        if "eventResult" not in item:
            continue

        file_job_id = item["jobId"]
        event_result_str = item["eventResult"]

        try:
            parsed = json.loads(event_result_str)
            event_detail = parsed.get("detail", {})
        except (json.JSONDecodeError, TypeError):
            continue

        file_transfer_id = event_detail.get("file-transfer-id", file_job_id)
        status = event_detail.get("status-code", "COMPLETED")
        file_path = event_detail.get("file-path", "")

        # Inject metadata into event detail for enriched event
        metadata = json.loads(master_record.get("metadata", "{}"))
        event_detail["_helper_metadata"] = metadata

        file_entry = {
            "fileTransferId": file_transfer_id,
            "status": status,
            "filePath": file_path,
            "eventDetail": json.dumps(event_detail),
        }

        hashed_key = _hash_key(file_transfer_id)

        try:
            table.update_item(
                Key={"jobId": master_job_id},
                UpdateExpression="SET #fs.#ftId = :entry ADD resolvedCount :one",
                ExpressionAttributeNames={"#fs": "fileStatuses", "#ftId": hashed_key},
                ExpressionAttributeValues={":entry": file_entry, ":one": 1},
                ConditionExpression="attribute_not_exists(#fs.#ftId)",
            )
        except table.meta.client.exceptions.ConditionalCheckFailedException:
            pass  # Duplicate — already counted
        except Exception as e:
            log_structured("ERROR", "Failed to update batch tracking for file",
                           master_job_id=master_job_id, file_transfer_id=file_transfer_id,
                           exception_type=type(e).__name__, exception_message=str(e))

        # Publish individual event if mode allows
        if should_publish_individual_event(master_record):
            from publish import publish_enriched_event
            publish_enriched_event(events_client, bus_name, event_result_str, master_record.get("metadata", "{}"), file_job_id)

    # After processing all files, check batch completion
    resp = table.get_item(Key={"jobId": master_job_id}, ConsistentRead=True)
    current = resp.get("Item", {})
    resolved_count = int(current.get("resolvedCount", 0))
    batch_published = current.get("batchEventPublished", False)

    _check_and_publish_batch(table, events_client, bus_name, current, resolved_count, expected_files, batch_published)
