"""Structured logging utility."""

import json
import logging

logger = logging.getLogger()


def log_structured(level: str, message: str, **kwargs) -> None:
    """Emit structured JSON log line."""
    entry = {"level": level, "message": message}
    entry.update(kwargs)
    logger.log(getattr(logging, level), json.dumps(entry))
