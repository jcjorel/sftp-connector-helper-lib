"""Enriched event publishing and metadata validation."""

import json

from log_util import log_structured


def validate_metadata(metadata_str: str) -> bool:
    """Validate metadata is a JSON object (dict)."""
    try:
        parsed = json.loads(metadata_str)
        return isinstance(parsed, dict)
    except (json.JSONDecodeError, TypeError):
        return False


def publish_enriched_event(
    events_client,
    bus_name: str,
    event_result_str: str,
    metadata_str: str,
    job_id: str,
) -> None:
    """Publish enriched event to EventBridge with _helper_metadata injected."""
    parsed_event = json.loads(event_result_str)

    source = parsed_event.get("source")
    detail_type = parsed_event.get("detail-type")
    detail = parsed_event.get("detail")

    if not source or not detail_type or detail is None:
        log_structured(
            "ERROR",
            "Stored event missing required keys, skipping",
            job_id=job_id,
            has_source=source is not None,
            has_detail_type=detail_type is not None,
            has_detail=detail is not None,
        )
        return

    # Inject _helper_metadata at top level of detail
    if isinstance(detail, str):
        try:
            detail = json.loads(detail)
        except (json.JSONDecodeError, TypeError):
            log_structured(
                "ERROR",
                "Stored event detail is malformed JSON string, skipping",
                job_id=job_id,
            )
            return

    if not isinstance(detail, dict):
        log_structured(
            "ERROR",
            "Stored event detail is not a dict, skipping",
            job_id=job_id,
            detail_type_actual=type(detail).__name__,
        )
        return

    metadata = json.loads(metadata_str)
    detail["_helper_metadata"] = metadata

    response = events_client.put_events(
        Entries=[
            {
                "Source": "custom.sftp-connector-helper",
                "DetailType": detail_type,
                "EventBusName": bus_name,
                "Detail": json.dumps(detail),
            }
        ]
    )

    if response.get("FailedEntryCount", 0) > 0:
        error_entry = response["Entries"][0]
        log_structured(
            "ERROR",
            "Failed to publish enriched event",
            job_id=job_id,
            error_code=error_entry.get("ErrorCode"),
            error_message=error_entry.get("ErrorMessage"),
        )


def publish_batch_completion_event(
    events_client,
    bus_name: str,
    detail: dict,
    detail_type: str,
) -> None:
    """Publish batch completion event to EventBridge."""
    response = events_client.put_events(
        Entries=[
            {
                "Source": "custom.sftp-connector-helper",
                "DetailType": detail_type,
                "EventBusName": bus_name,
                "Detail": json.dumps(detail),
            }
        ]
    )

    if response.get("FailedEntryCount", 0) > 0:
        error_entry = response["Entries"][0]
        log_structured(
            "ERROR",
            "Failed to publish batch completion event",
            transfer_id=detail.get("transfer-id"),
            error_code=error_entry.get("ErrorCode"),
            error_message=error_entry.get("ErrorMessage"),
        )
