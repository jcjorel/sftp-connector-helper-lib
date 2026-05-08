"""Centralized structured logging utility for all Lambdas."""

import json
import logging

logger = logging.getLogger()


def log_structured(level: str, message: str, **kwargs) -> None:
    """Emit structured JSON log line with optional timing fields.

    If 'detail' is passed as a kwarg (dict), extracts start-timestamp
    and end-timestamp automatically, then removes 'detail' from output.
    """
    detail = kwargs.pop("detail", None)
    entry = {"level": level, "message": message}
    if detail and isinstance(detail, dict):
        if "start-timestamp" in detail:
            entry["start_timestamp"] = detail["start-timestamp"]
        if "end-timestamp" in detail:
            entry["end_timestamp"] = detail["end-timestamp"]
    entry.update(kwargs)
    logger.log(getattr(logging, level), json.dumps(entry))
