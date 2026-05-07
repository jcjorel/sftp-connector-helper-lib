"""Shared fixtures for Joiner Lambda tests."""

import json
import os
from unittest.mock import MagicMock, patch

import pytest


@pytest.fixture(autouse=True)
def env_vars(monkeypatch):
    """Set required environment variables."""
    monkeypatch.setenv("EVENT_BUS_NAME", "test-bus")


@pytest.fixture
def mock_events():
    """Mock boto3 EventBridge client."""
    with patch("handler.events") as mock_client:
        mock_client.put_events = MagicMock(return_value={})
        yield mock_client


@pytest.fixture
def mock_cloudwatch():
    """Mock boto3 CloudWatch client."""
    with patch("handler.cloudwatch") as mock_client:
        mock_client.put_metric_data = MagicMock(return_value={})
        yield mock_client


@pytest.fixture
def mock_table():
    """Mock DynamoDB Table resource."""
    with patch("handler.table") as mock_tbl:
        yield mock_tbl


def make_sqs_event(*stream_records: dict) -> dict:
    """Build an SQS event wrapping DynamoDB Stream records."""
    return {
        "Records": [
            {"messageId": f"msg-{i}", "body": json.dumps(rec)}
            for i, rec in enumerate(stream_records)
        ]
    }


def make_stream_record(
    event_name: str,
    job_id: str,
    new_image: dict | None = None,
    old_image: dict | None = None,
) -> dict:
    """Build a DynamoDB Stream record."""
    record = {
        "eventID": "test-event-id",
        "eventName": event_name,
        "dynamodb": {
            "Keys": {"jobId": {"S": job_id}},
        },
    }
    if new_image is not None:
        record["dynamodb"]["NewImage"] = new_image
    if old_image is not None:
        record["dynamodb"]["OldImage"] = old_image
    return record


def make_dynamo_image(
    job_id: str,
    metadata: str | None = None,
    event_result: str | None = None,
    transfer_id: str | None = None,
) -> dict:
    """Build a DynamoDB image with typed attributes."""
    image = {"jobId": {"S": job_id}, "ttl": {"N": "1715200000"}}
    if metadata is not None:
        image["metadata"] = {"S": metadata}
    if event_result is not None:
        image["eventResult"] = {"S": event_result}
    if transfer_id is not None:
        image["transferId"] = {"S": transfer_id}
    return image


SAMPLE_EVENT_RESULT = json.dumps({
    "source": "aws.transfer",
    "detail-type": "SFTP Connector Directory Listing Completed",
    "detail": {
        "connector-id": "c-01234",
        "listing-id": "listing-abc-123",
        "status": "COMPLETED",
    },
})

SAMPLE_METADATA = json.dumps({"orderId": "ORD-456"})
