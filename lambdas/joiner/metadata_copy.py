"""Metadata copy logic for StartFileTransfer fan-out."""

from boto3.dynamodb.conditions import Key

from log_util import log_structured

GSI_NAME = "transferId-index"


def copy_metadata_from_master(table, transfer_id: str, per_file_job_id: str) -> None:
    """Read master record by transfer_id; if it has metadata, copy to per-file record."""
    response = table.get_item(Key={"jobId": transfer_id})
    master = response.get("Item")
    if not master:
        log_structured("INFO", "Master record not found", transfer_id=transfer_id, per_file_job_id=per_file_job_id)
        return

    metadata = master.get("metadata")
    if not metadata:
        log_structured("INFO", "Master has no metadata yet", transfer_id=transfer_id, per_file_job_id=per_file_job_id)
        return

    table.update_item(
        Key={"jobId": per_file_job_id},
        UpdateExpression="SET metadata = :m",
        ExpressionAttributeValues={":m": metadata},
    )
    log_structured("INFO", "Copied metadata from master to per-file record", transfer_id=transfer_id, per_file_job_id=per_file_job_id)


def fan_out_metadata_to_per_file_records(table, master_job_id: str, metadata: str) -> None:
    """Query GSI for per-file records and copy metadata to eligible ones."""
    kwargs = {
        "IndexName": GSI_NAME,
        "KeyConditionExpression": Key("transferId").eq(master_job_id),
    }
    copied = 0
    while True:
        response = table.query(**kwargs)
        for item in response.get("Items", []):
            if "eventResult" in item and "metadata" not in item:
                try:
                    table.update_item(
                        Key={"jobId": item["jobId"]},
                        UpdateExpression="SET metadata = :m",
                        ExpressionAttributeValues={":m": metadata},
                    )
                    copied += 1
                except Exception:
                    log_structured("WARNING", "Failed to copy metadata to per-file record", master_job_id=master_job_id, per_file_job_id=item["jobId"])
        if "LastEvaluatedKey" not in response:
            break
        kwargs["ExclusiveStartKey"] = response["LastEvaluatedKey"]

    log_structured("INFO", "Fan-out metadata complete", master_job_id=master_job_id, records_copied=copied)
