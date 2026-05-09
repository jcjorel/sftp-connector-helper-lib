"""Maps SFTP Connector detail-type prefixes to the ID field name in event detail."""


class UnknownOperationError(Exception):
    """Raised when the detail-type does not match any known operation."""

OPERATION_MAP = {
    "SFTP Connector File Send": {
        "id_field": "transfer-id",
        "operation_type": "FILE_TRANSFER",
    },
    "SFTP Connector File Retrieve": {
        "id_field": "transfer-id",
        "operation_type": "FILE_TRANSFER",
    },
    "SFTP Connector Directory Listing": {
        "id_field": "listing-id",
        "operation_type": "DIRECTORY_LISTING",
    },
    "SFTP Connector Remote Move": {
        "id_field": "move-id",
        "operation_type": "REMOTE_MOVE",
    },
    "SFTP Connector Remote Delete": {
        "id_field": "delete-id",
        "operation_type": "REMOTE_DELETE",
    },
}


def get_operation_info(detail_type: str) -> dict:
    """Return operation info dict for the given detail-type.

    Matches by prefix (detail-type may end with ' Completed', ' Failed', etc.).
    Raises KeyError if no matching operation found.
    """
    for prefix, info in OPERATION_MAP.items():
        if detail_type.startswith(prefix):
            return info
    raise UnknownOperationError(f"Unknown detail-type: {detail_type}")


def get_job_id(event_detail: dict, detail_type: str) -> tuple[str, str, bool]:
    """Extract job ID, operation type, and per-file flag from event.

    Returns:
        (job_id, operation_type, is_per_file)
    """
    info = get_operation_info(detail_type)
    operation_type = info["operation_type"]

    # Per-file FILE_TRANSFER detection
    if (
        operation_type == "FILE_TRANSFER"
        and "file-transfer-id" in event_detail
        and "transfer-id" in event_detail
    ):
        return event_detail["file-transfer-id"], operation_type, True

    return event_detail[info["id_field"]], operation_type, False
