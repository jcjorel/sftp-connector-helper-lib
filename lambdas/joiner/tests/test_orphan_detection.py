"""Tests for orphan detection logic."""

import json
import sys
import os
from unittest.mock import MagicMock, patch

import pytest

# conftest fixtures are auto-loaded; import helpers via sys.path
sys.path.insert(0, os.path.dirname(__file__))
from conftest import SAMPLE_EVENT_RESULT, SAMPLE_METADATA, make_dynamo_image, make_pipe_event, make_stream_record


class TestOrphanDetectionUnit:
    """Unit tests for orphan_detection module directly."""

    @pytest.fixture
    def mock_table(self):
        return MagicMock()

    @pytest.fixture
    def mock_sns_client(self):
        client = MagicMock()
        client.publish = MagicMock(return_value={})
        return client

    @pytest.fixture
    def mock_cw_client(self):
        client = MagicMock()
        client.put_metric_data = MagicMock(return_value={})
        return client

    def test_metadata_only_orphan_emits_metric_and_sns(self, mock_table, mock_sns_client, mock_cw_client):
        """Metadata present, no eventResult → orphan."""
        from orphan_detection import detect_orphan

        old_image = {"metadata": json.dumps({"orderId": "ORD-1"}), "jobId": "job-1"}

        detect_orphan(mock_table, old_image, "job-1", mock_sns_client, "arn:aws:sns:us-east-1:123:topic", mock_cw_client)

        mock_cw_client.put_metric_data.assert_called_once()
        metric_data = mock_cw_client.put_metric_data.call_args[1]["MetricData"]
        assert metric_data[0]["MetricName"] == "OrphanedRecords"
        assert metric_data[0]["Dimensions"][0]["Value"] == "UNKNOWN"
        assert metric_data[1]["MetricName"] == "OrphanedRecords"
        assert "Dimensions" not in metric_data[1]

        mock_sns_client.publish.assert_called_once()
        sns_call = mock_sns_client.publish.call_args[1]
        assert "job-1" in sns_call["Subject"]
        body = json.loads(sns_call["Message"])
        assert body["orphan_type"] == "metadata-only"
        assert body["connector_id"] == "UNKNOWN"

    def test_event_only_orphan_emits_metric_and_sns(self, mock_table, mock_sns_client, mock_cw_client):
        """eventResult present, no metadata → orphan."""
        from orphan_detection import detect_orphan

        old_image = {"eventResult": SAMPLE_EVENT_RESULT, "jobId": "job-2"}

        detect_orphan(mock_table, old_image, "job-2", mock_sns_client, "arn:aws:sns:us-east-1:123:topic", mock_cw_client)

        mock_cw_client.put_metric_data.assert_called_once()
        metric_data = mock_cw_client.put_metric_data.call_args[1]["MetricData"]
        assert metric_data[0]["Dimensions"][0]["Value"] == "c-01234"

        mock_sns_client.publish.assert_called_once()
        body = json.loads(mock_sns_client.publish.call_args[1]["Message"])
        assert body["orphan_type"] == "event-only"
        assert body["connector_id"] == "c-01234"

    def test_master_zero_per_file_records_is_orphan(self, mock_table, mock_sns_client, mock_cw_client):
        """FILE_TRANSFER master with zero per-file records → orphan."""
        from orphan_detection import detect_orphan

        mock_table.query.return_value = {"Count": 0, "Items": []}
        old_image = {"operationType": "FILE_TRANSFER", "jobId": "transfer-1"}

        detect_orphan(mock_table, old_image, "transfer-1", mock_sns_client, "arn:aws:sns:us-east-1:123:topic", mock_cw_client)

        mock_table.query.assert_called_once()
        query_kwargs = mock_table.query.call_args[1]
        assert query_kwargs["IndexName"] == "transferId-index"
        assert query_kwargs["Limit"] == 1

        mock_cw_client.put_metric_data.assert_called_once()
        mock_sns_client.publish.assert_called_once()
        body = json.loads(mock_sns_client.publish.call_args[1]["Message"])
        assert body["orphan_type"] == "master-no-files"

    def test_master_with_per_file_records_is_ignored(self, mock_table, mock_sns_client, mock_cw_client):
        """FILE_TRANSFER master with ≥1 per-file records → normal expiry."""
        from orphan_detection import detect_orphan

        mock_table.query.return_value = {"Count": 1, "Items": [{"jobId": "file-1"}]}
        old_image = {"operationType": "FILE_TRANSFER", "jobId": "transfer-1"}

        detect_orphan(mock_table, old_image, "transfer-1", mock_sns_client, "arn:aws:sns:us-east-1:123:topic", mock_cw_client)

        mock_cw_client.put_metric_data.assert_not_called()
        mock_sns_client.publish.assert_not_called()

    def test_normal_expiry_both_fields_ignored(self, mock_table, mock_sns_client, mock_cw_client):
        """Both metadata AND eventResult present → ignore."""
        from orphan_detection import detect_orphan

        old_image = {"metadata": SAMPLE_METADATA, "eventResult": SAMPLE_EVENT_RESULT, "jobId": "job-3"}

        detect_orphan(mock_table, old_image, "job-3", mock_sns_client, "arn:aws:sns:us-east-1:123:topic", mock_cw_client)

        mock_cw_client.put_metric_data.assert_not_called()
        mock_sns_client.publish.assert_not_called()

    def test_connector_id_extracted_from_event_result(self, mock_table, mock_sns_client, mock_cw_client):
        """ConnectorId extracted from eventResult detail.connector-id."""
        from orphan_detection import detect_orphan

        event_result = json.dumps({"detail": {"connector-id": "c-99999"}})
        old_image = {"eventResult": event_result, "jobId": "job-4"}

        detect_orphan(mock_table, old_image, "job-4", mock_sns_client, "arn:aws:sns:us-east-1:123:topic", mock_cw_client)

        metric_data = mock_cw_client.put_metric_data.call_args[1]["MetricData"]
        assert metric_data[0]["Dimensions"][0]["Value"] == "c-99999"

    def test_connector_id_fallback_unknown_on_bad_json(self, mock_table, mock_sns_client, mock_cw_client):
        """ConnectorId falls back to UNKNOWN when eventResult is invalid JSON."""
        from orphan_detection import detect_orphan

        old_image = {"eventResult": "not-json", "jobId": "job-5"}

        detect_orphan(mock_table, old_image, "job-5", mock_sns_client, "arn:aws:sns:us-east-1:123:topic", mock_cw_client)

        metric_data = mock_cw_client.put_metric_data.call_args[1]["MetricData"]
        assert metric_data[0]["Dimensions"][0]["Value"] == "UNKNOWN"


class TestOrphanDetectionIntegration:
    """Integration tests via handler processing REMOVE records."""

    def test_remove_with_metadata_only_triggers_orphan(self, mock_table, mock_cloudwatch, mock_sns, monkeypatch):
        """REMOVE record with metadata-only old image triggers orphan detection."""
        monkeypatch.setenv("SNS_TOPIC_ARN", "arn:aws:sns:us-east-1:123:topic")

        import handler
        handler.SNS_TOPIC_ARN = "arn:aws:sns:us-east-1:123:topic"

        old_image = make_dynamo_image("job-int-1", metadata=SAMPLE_METADATA)
        stream_record = make_stream_record("REMOVE", "job-int-1", old_image=old_image)
        event = make_pipe_event(stream_record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_cloudwatch.put_metric_data.assert_called_once()

    def test_remove_with_both_fields_no_orphan(self, mock_table, mock_cloudwatch, mock_sns, monkeypatch):
        """REMOVE record with both fields → no orphan, no metric."""
        monkeypatch.setenv("SNS_TOPIC_ARN", "arn:aws:sns:us-east-1:123:topic")

        import handler
        handler.SNS_TOPIC_ARN = "arn:aws:sns:us-east-1:123:topic"

        old_image = make_dynamo_image("job-int-2", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        stream_record = make_stream_record("REMOVE", "job-int-2", old_image=old_image)
        event = make_pipe_event(stream_record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_cloudwatch.put_metric_data.assert_not_called()

    def test_remove_without_old_image_no_error(self, mock_table, mock_cloudwatch, mock_sns):
        """REMOVE record without OldImage → no crash, no orphan."""
        import handler

        stream_record = make_stream_record("REMOVE", "job-int-3")
        event = make_pipe_event(stream_record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_cloudwatch.put_metric_data.assert_not_called()
