"""Unit tests for batch_timeout module."""

import json
import time
from unittest.mock import MagicMock, patch

import pytest

from batch_timeout import _assemble_timeout_event, process_timeout_message, schedule_batch_timeout


@pytest.fixture
def mock_table():
    tbl = MagicMock()
    tbl.meta.client.exceptions.ConditionalCheckFailedException = type(
        "ConditionalCheckFailedException", (Exception,), {}
    )
    return tbl


@pytest.fixture
def mock_sqs():
    return MagicMock()


@pytest.fixture
def mock_events():
    return MagicMock()


@pytest.fixture
def master_image():
    return {
        "jobId": "t-abc123",
        "batchTimeoutAt": int(time.time()) + 3600,
        "connectorId": "c-01234",
        "expectedFiles": 3,
        "transferDirection": "SEND",
        "metadata": json.dumps({"orderId": "ORD-001"}),
        "filePaths": ["/f1.csv", "/f2.csv", "/f3.csv"],
    }


class TestScheduleBatchTimeout:
    def test_sends_sqs_message_and_writes_marker(self, mock_table, mock_sqs, master_image):
        mock_table.get_item.return_value = {"Item": {}}

        schedule_batch_timeout(mock_table, mock_sqs, "https://queue-url", master_image)

        mock_sqs.send_message.assert_called_once()
        call_kwargs = mock_sqs.send_message.call_args[1]
        assert call_kwargs["QueueUrl"] == "https://queue-url"
        assert call_kwargs["DelaySeconds"] <= 900
        body = json.loads(call_kwargs["MessageBody"])
        assert body["transferId"] == "t-abc123"
        assert body["expectedFiles"] == 3
        assert body["filePaths"] == ["/f1.csv", "/f2.csv", "/f3.csv"]

        mock_table.update_item.assert_called_once()

    def test_skips_if_already_scheduled(self, mock_table, mock_sqs, master_image):
        mock_table.get_item.return_value = {"Item": {"batchTimeoutScheduled": True}}

        schedule_batch_timeout(mock_table, mock_sqs, "https://queue-url", master_image)

        mock_sqs.send_message.assert_not_called()
        mock_table.update_item.assert_not_called()

    def test_ignores_conditional_check_failure(self, mock_table, mock_sqs, master_image):
        mock_table.get_item.return_value = {"Item": {}}
        mock_table.update_item.side_effect = (
            mock_table.meta.client.exceptions.ConditionalCheckFailedException()
        )

        # Should not raise
        schedule_batch_timeout(mock_table, mock_sqs, "https://queue-url", master_image)
        mock_sqs.send_message.assert_called_once()


class TestProcessTimeoutMessage:
    def _make_body(self, target_offset=0):
        return {
            "transferId": "t-abc123",
            "targetTimeoutAt": int(time.time()) + target_offset,
            "connectorId": "c-01234",
            "expectedFiles": 3,
            "transferDirection": "SEND",
            "metadata": json.dumps({"orderId": "ORD-001"}),
            "filePaths": ["/f1.csv", "/f2.csv", "/f3.csv"],
        }

    def test_hops_when_not_yet_time(self, mock_table, mock_sqs, mock_events):
        body = self._make_body(target_offset=1800)  # 30 min in future

        process_timeout_message(mock_table, mock_sqs, "https://q", mock_events, "bus", body)

        mock_sqs.send_message.assert_called_once()
        call_kwargs = mock_sqs.send_message.call_args[1]
        assert call_kwargs["DelaySeconds"] <= 900
        mock_table.get_item.assert_not_called()

    def test_fires_when_time_reached(self, mock_table, mock_sqs, mock_events):
        body = self._make_body(target_offset=-10)  # Already past
        mock_table.get_item.return_value = {
            "Item": {"resolvedCount": 1, "fileStatuses": {
                "_init": {},
                "hash1": {"fileTransferId": "ft-1", "status": "COMPLETED", "filePath": "/f1.csv",
                          "eventDetail": json.dumps({"file-transfer-id": "ft-1", "status-code": "COMPLETED", "file-path": "/f1.csv"})},
            }}
        }
        mock_events.put_events.return_value = {"FailedEntryCount": 0}

        process_timeout_message(mock_table, mock_sqs, "https://q", mock_events, "bus", body)

        # Should publish event
        mock_events.put_events.assert_called_once()
        entry = mock_events.put_events.call_args[1]["Entries"][0]
        assert "Timed Out" in entry["DetailType"]
        detail = json.loads(entry["Detail"])
        assert detail["status-code"] == "TIMED_OUT"
        assert detail["completed-count"] == 1
        assert detail["timed-out-count"] == 2

        # Should write dedup marker
        mock_table.update_item.assert_called_once()

    def test_skips_if_batch_event_already_published(self, mock_table, mock_sqs, mock_events):
        body = self._make_body(target_offset=-10)
        mock_table.get_item.return_value = {
            "Item": {"resolvedCount": 3, "batchEventPublished": True, "fileStatuses": {}}
        }

        process_timeout_message(mock_table, mock_sqs, "https://q", mock_events, "bus", body)

        mock_events.put_events.assert_not_called()

    def test_skips_if_timeout_already_published(self, mock_table, mock_sqs, mock_events):
        body = self._make_body(target_offset=-10)
        mock_table.get_item.return_value = {
            "Item": {"resolvedCount": 1, "batchTimeoutPublished": True, "fileStatuses": {}}
        }

        process_timeout_message(mock_table, mock_sqs, "https://q", mock_events, "bus", body)

        mock_events.put_events.assert_not_called()

    def test_skips_if_all_resolved(self, mock_table, mock_sqs, mock_events):
        body = self._make_body(target_offset=-10)
        mock_table.get_item.return_value = {
            "Item": {"resolvedCount": 3, "fileStatuses": {}}
        }

        process_timeout_message(mock_table, mock_sqs, "https://q", mock_events, "bus", body)

        mock_events.put_events.assert_not_called()

    def test_publishes_degraded_event_when_record_ttld(self, mock_table, mock_sqs, mock_events):
        body = self._make_body(target_offset=-10)
        mock_table.get_item.return_value = {}  # No Item
        mock_events.put_events.return_value = {"FailedEntryCount": 0}

        process_timeout_message(mock_table, mock_sqs, "https://q", mock_events, "bus", body)

        mock_events.put_events.assert_called_once()
        entry = mock_events.put_events.call_args[1]["Entries"][0]
        detail = json.loads(entry["Detail"])
        assert detail["timed-out-count"] == 3


class TestAssembleTimeoutEvent:
    def test_assembles_mixed_statuses(self):
        msg = {
            "transferId": "t-abc",
            "connectorId": "c-01234",
            "expectedFiles": 3,
            "transferDirection": "SEND",
            "metadata": json.dumps({"orderId": "ORD-001"}),
            "filePaths": ["/f1.csv", "/f2.csv", "/f3.csv"],
        }
        item = {
            "fileStatuses": {
                "_init": {},
                "h1": {
                    "fileTransferId": "ft-1", "status": "COMPLETED", "filePath": "/f1.csv",
                    "eventDetail": json.dumps({"file-transfer-id": "ft-1", "status-code": "COMPLETED", "file-path": "/f1.csv"}),
                },
            }
        }

        detail, detail_type = _assemble_timeout_event(msg, item)

        assert detail["status-code"] == "TIMED_OUT"
        assert detail["completed-count"] == 1
        assert detail["timed-out-count"] == 2
        assert detail["file-count"] == 3
        assert "Send" in detail_type
        assert len(detail["files"]) == 3

    def test_retrieve_direction(self):
        msg = {
            "transferId": "t-abc",
            "connectorId": "c-01234",
            "expectedFiles": 1,
            "transferDirection": "RETRIEVE",
            "metadata": json.dumps({}),
            "filePaths": ["/f1.csv"],
        }
        item = {"fileStatuses": {}}

        detail, detail_type = _assemble_timeout_event(msg, item)

        assert "Retrieve" in detail_type
        assert detail["timed-out-count"] == 1
