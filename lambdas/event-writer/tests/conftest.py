"""Shared fixtures for Event Writer Lambda tests."""

import json
import os
from unittest.mock import MagicMock, patch

import pytest


@pytest.fixture(autouse=True)
def env_vars(monkeypatch):
    """Set required environment variables."""
    monkeypatch.setenv("TABLE_NAME", "test-table")
    monkeypatch.setenv("TTL_SECONDS", "3600")


@pytest.fixture
def mock_dynamodb():
    """Mock boto3 DynamoDB client."""
    with patch("handler.dynamodb") as mock_client:
        mock_client.update_item = MagicMock(return_value={})
        yield mock_client


def make_sqs_event(*eb_events: dict) -> dict:
    """Build an SQS event wrapping one or more EventBridge events."""
    return {
        "Records": [{"body": json.dumps(ev)} for ev in eb_events]
    }


def make_eb_event(detail_type: str, detail: dict) -> dict:
    """Build a minimal EventBridge event."""
    return {
        "version": "0",
        "source": "aws.transfer",
        "detail-type": detail_type,
        "detail": detail,
    }


@pytest.fixture
def file_transfer_master_event():
    """Master FILE_TRANSFER event (no file-transfer-id)."""
    return make_eb_event(
        "SFTP Connector File Send Completed",
        {
            "transfer-id": "t-abc123",
            "connector-id": "c-01234567890abcdef",
            "status-code": "COMPLETED",
        },
    )


@pytest.fixture
def file_transfer_per_file_event():
    """Per-file FILE_TRANSFER event (has both transfer-id and file-transfer-id)."""
    return make_eb_event(
        "SFTP Connector File Send Completed",
        {
            "transfer-id": "t-abc123",
            "file-transfer-id": "ft-xyz789",
            "connector-id": "c-01234567890abcdef",
            "status-code": "COMPLETED",
        },
    )


@pytest.fixture
def directory_listing_event():
    """DIRECTORY_LISTING event."""
    return make_eb_event(
        "SFTP Connector Directory Listing Completed",
        {
            "listing-id": "l-list001",
            "connector-id": "c-01234567890abcdef",
            "status-code": "COMPLETED",
        },
    )


@pytest.fixture
def remote_move_event():
    """REMOTE_MOVE event."""
    return make_eb_event(
        "SFTP Connector Remote Move Completed",
        {
            "move-id": "m-move001",
            "connector-id": "c-01234567890abcdef",
            "status-code": "COMPLETED",
        },
    )


@pytest.fixture
def remote_delete_event():
    """REMOTE_DELETE event."""
    return make_eb_event(
        "SFTP Connector Remote Delete Completed",
        {
            "delete-id": "d-del001",
            "connector-id": "c-01234567890abcdef",
            "status-code": "COMPLETED",
        },
    )
