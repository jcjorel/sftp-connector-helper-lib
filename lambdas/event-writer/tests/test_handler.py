"""Unit tests for Event Writer Lambda handler."""

import json
import time
from unittest.mock import patch

from tests.conftest import make_eb_event


class TestFileTransferMaster:
    """Test master FILE_TRANSFER event (keyed by transfer-id, no transferId attr)."""

    def test_stores_event_keyed_by_transfer_id(self, mock_dynamodb, file_transfer_master_event):
        from handler import lambda_handler

        with patch("handler.time.time", return_value=1000000):
            lambda_handler(file_transfer_master_event, None)

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

        with patch("handler.time.time", return_value=1000000):
            lambda_handler(file_transfer_per_file_event, None)

        call_kwargs = mock_dynamodb.update_item.call_args[1]

        assert call_kwargs["Key"] == {"jobId": {"S": "ft-xyz789"}}
        assert "transferId = :tid" in call_kwargs["UpdateExpression"]
        assert call_kwargs["ExpressionAttributeValues"][":tid"] == {"S": "t-abc123"}


class TestDirectoryListing:
    """Test DIRECTORY_LISTING event (keyed by listing-id)."""

    def test_stores_event_keyed_by_listing_id(self, mock_dynamodb, directory_listing_event):
        from handler import lambda_handler

        with patch("handler.time.time", return_value=2000000):
            lambda_handler(directory_listing_event, None)

        call_kwargs = mock_dynamodb.update_item.call_args[1]
        assert call_kwargs["Key"] == {"jobId": {"S": "l-list001"}}
        assert call_kwargs["ExpressionAttributeValues"][":t"] == {"N": str(2000000 + 3600)}


class TestRemoteMove:
    """Test REMOTE_MOVE event (keyed by move-id)."""

    def test_stores_event_keyed_by_move_id(self, mock_dynamodb, remote_move_event):
        from handler import lambda_handler

        lambda_handler(remote_move_event, None)

        call_kwargs = mock_dynamodb.update_item.call_args[1]
        assert call_kwargs["Key"] == {"jobId": {"S": "m-move001"}}


class TestRemoteDelete:
    """Test REMOTE_DELETE event (keyed by delete-id)."""

    def test_stores_event_keyed_by_delete_id(self, mock_dynamodb, remote_delete_event):
        from handler import lambda_handler

        lambda_handler(remote_delete_event, None)

        call_kwargs = mock_dynamodb.update_item.call_args[1]
        assert call_kwargs["Key"] == {"jobId": {"S": "d-del001"}}


class TestIdempotency:
    """Test idempotency — same event twice produces same UpdateItem, no error."""

    def test_same_event_twice_no_error(self, mock_dynamodb, file_transfer_master_event):
        from handler import lambda_handler

        with patch("handler.time.time", return_value=1000000):
            lambda_handler(file_transfer_master_event, None)
            lambda_handler(file_transfer_master_event, None)

        assert mock_dynamodb.update_item.call_count == 2
        first_call = mock_dynamodb.update_item.call_args_list[0]
        second_call = mock_dynamodb.update_item.call_args_list[1]
        assert first_call == second_call


class TestStructuredLogging:
    """Test structured log output contains mandatory fields."""

    def test_log_contains_mandatory_fields(self, mock_dynamodb, file_transfer_master_event, caplog):
        import logging
        from handler import lambda_handler

        with caplog.at_level(logging.INFO):
            lambda_handler(file_transfer_master_event, None)

        log_entries = [json.loads(r.message) for r in caplog.records if r.message.startswith("{")]
        assert len(log_entries) >= 1

        for entry in log_entries:
            assert "level" in entry
            assert "message" in entry
            assert "job_id" in entry
            assert "operation" in entry
            assert "connector_id" in entry

    def test_log_contains_timing_fields(self, mock_dynamodb, file_transfer_master_event, caplog):
        import logging
        from handler import lambda_handler

        with caplog.at_level(logging.INFO):
            lambda_handler(file_transfer_master_event, None)

        log_entries = [json.loads(r.message) for r in caplog.records if r.message.startswith("{")]
        assert len(log_entries) >= 1

        for entry in log_entries:
            assert entry["start_timestamp"] == "2024-01-24T18:28:07.632388Z"
            assert entry["end_timestamp"] == "2024-01-24T18:28:07.774898Z"


class TestUnknownOperationType:
    """Test unknown operation type handling."""

    def _make_unknown_event(self):
        return make_eb_event(
            "SFTP Connector FutureOp Completed",
            {
                "future-id": "f-future001",
                "connector-id": "c-01234567890abcdef",
                "status-code": "COMPLETED",
            },
        )

    def test_emits_metric_with_connector_id_dimension(self, mock_dynamodb, mock_cloudwatch):
        from handler import lambda_handler

        lambda_handler(self._make_unknown_event(), None)

        mock_cloudwatch.put_metric_data.assert_called_once()
        call_kwargs = mock_cloudwatch.put_metric_data.call_args[1]
        assert call_kwargs["Namespace"] == "SftpConnectorHelper"
        metric_with_dim = call_kwargs["MetricData"][0]
        assert metric_with_dim["MetricName"] == "UnknownOperationType"
        assert metric_with_dim["Dimensions"] == [{"Name": "ConnectorId", "Value": "c-01234567890abcdef"}]

    def test_emits_metric_without_dimension_aggregate(self, mock_dynamodb, mock_cloudwatch):
        from handler import lambda_handler

        lambda_handler(self._make_unknown_event(), None)

        call_kwargs = mock_cloudwatch.put_metric_data.call_args[1]
        metric_no_dim = call_kwargs["MetricData"][1]
        assert metric_no_dim["MetricName"] == "UnknownOperationType"
        assert "Dimensions" not in metric_no_dim

    def test_logs_warning_with_operation_and_connector_id(self, mock_dynamodb, mock_cloudwatch, caplog):
        import logging
        from handler import lambda_handler

        with caplog.at_level(logging.WARNING):
            lambda_handler(self._make_unknown_event(), None)

        log_entries = [json.loads(r.message) for r in caplog.records if r.message.startswith("{") and "WARNING" in r.message]
        assert len(log_entries) >= 1
        entry = log_entries[0]
        assert entry["level"] == "WARNING"
        assert entry["operation"] == "SFTP Connector FutureOp Completed"
        assert entry["connector_id"] == "c-01234567890abcdef"

    def test_returns_successfully_no_exception(self, mock_dynamodb, mock_cloudwatch):
        from handler import lambda_handler

        result = lambda_handler(self._make_unknown_event(), None)
        mock_dynamodb.update_item.assert_not_called()
