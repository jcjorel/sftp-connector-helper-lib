"""Unit tests for Event Writer Lambda handler."""

import json
import time
from unittest.mock import patch

from tests.conftest import make_eb_event, make_sqs_event


class TestFileTransferMaster:
    """Test master FILE_TRANSFER event (keyed by transfer-id, no transferId attr)."""

    def test_stores_event_keyed_by_transfer_id(self, mock_dynamodb, file_transfer_master_event):
        from handler import lambda_handler

        sqs_event = make_sqs_event(file_transfer_master_event)

        with patch("handler.time.time", return_value=1000000):
            lambda_handler(sqs_event, None)

        mock_dynamodb.update_item.assert_called_once()
        call_kwargs = mock_dynamodb.update_item.call_args[1]

        assert call_kwargs["Key"] == {"jobId": {"S": "t-abc123"}}
        assert call_kwargs["UpdateExpression"] == "SET eventResult = :e, #t = :t"
        assert call_kwargs["ExpressionAttributeNames"] == {"#t": "ttl"}
        assert ":tid" not in call_kwargs["ExpressionAttributeValues"]
        assert call_kwargs["ExpressionAttributeValues"][":e"] == {
            "S": json.dumps(file_transfer_master_event)
        }
        assert call_kwargs["ExpressionAttributeValues"][":t"] == {"N": str(1000000 + 3600)}


class TestFileTransferPerFile:
    """Test per-file FILE_TRANSFER event (keyed by file-transfer-id, includes transferId)."""

    def test_stores_event_keyed_by_file_transfer_id(self, mock_dynamodb, file_transfer_per_file_event):
        from handler import lambda_handler

        sqs_event = make_sqs_event(file_transfer_per_file_event)

        with patch("handler.time.time", return_value=1000000):
            lambda_handler(sqs_event, None)

        call_kwargs = mock_dynamodb.update_item.call_args[1]

        assert call_kwargs["Key"] == {"jobId": {"S": "ft-xyz789"}}
        assert "transferId = :tid" in call_kwargs["UpdateExpression"]
        assert call_kwargs["ExpressionAttributeValues"][":tid"] == {"S": "t-abc123"}


class TestDirectoryListing:
    """Test DIRECTORY_LISTING event (keyed by listing-id)."""

    def test_stores_event_keyed_by_listing_id(self, mock_dynamodb, directory_listing_event):
        from handler import lambda_handler

        sqs_event = make_sqs_event(directory_listing_event)

        with patch("handler.time.time", return_value=2000000):
            lambda_handler(sqs_event, None)

        call_kwargs = mock_dynamodb.update_item.call_args[1]
        assert call_kwargs["Key"] == {"jobId": {"S": "l-list001"}}
        assert call_kwargs["ExpressionAttributeValues"][":t"] == {"N": str(2000000 + 3600)}


class TestRemoteMove:
    """Test REMOTE_MOVE event (keyed by move-id)."""

    def test_stores_event_keyed_by_move_id(self, mock_dynamodb, remote_move_event):
        from handler import lambda_handler

        sqs_event = make_sqs_event(remote_move_event)
        lambda_handler(sqs_event, None)

        call_kwargs = mock_dynamodb.update_item.call_args[1]
        assert call_kwargs["Key"] == {"jobId": {"S": "m-move001"}}


class TestRemoteDelete:
    """Test REMOTE_DELETE event (keyed by delete-id)."""

    def test_stores_event_keyed_by_delete_id(self, mock_dynamodb, remote_delete_event):
        from handler import lambda_handler

        sqs_event = make_sqs_event(remote_delete_event)
        lambda_handler(sqs_event, None)

        call_kwargs = mock_dynamodb.update_item.call_args[1]
        assert call_kwargs["Key"] == {"jobId": {"S": "d-del001"}}


class TestIdempotency:
    """Test idempotency — same event twice produces same UpdateItem, no error."""

    def test_same_event_twice_no_error(self, mock_dynamodb, file_transfer_master_event):
        from handler import lambda_handler

        sqs_event = make_sqs_event(file_transfer_master_event)

        with patch("handler.time.time", return_value=1000000):
            lambda_handler(sqs_event, None)
            lambda_handler(sqs_event, None)

        assert mock_dynamodb.update_item.call_count == 2
        # Both calls have identical arguments
        first_call = mock_dynamodb.update_item.call_args_list[0]
        second_call = mock_dynamodb.update_item.call_args_list[1]
        assert first_call == second_call


class TestSqsBatch:
    """Test SQS batch — multiple records in one invocation."""

    def test_processes_all_records(self, mock_dynamodb, file_transfer_master_event, directory_listing_event):
        from handler import lambda_handler

        sqs_event = make_sqs_event(file_transfer_master_event, directory_listing_event)
        lambda_handler(sqs_event, None)

        assert mock_dynamodb.update_item.call_count == 2


class TestStructuredLogging:
    """Test structured log output contains mandatory fields."""

    def test_log_contains_mandatory_fields(self, mock_dynamodb, file_transfer_master_event, caplog):
        import logging
        from handler import lambda_handler

        sqs_event = make_sqs_event(file_transfer_master_event)

        with caplog.at_level(logging.INFO):
            lambda_handler(sqs_event, None)

        # Find structured log entries
        log_entries = [json.loads(r.message) for r in caplog.records if r.message.startswith("{")]
        assert len(log_entries) >= 1

        for entry in log_entries:
            assert "level" in entry
            assert "message" in entry
            assert "job_id" in entry
            assert "operation" in entry
            assert "connector_id" in entry
