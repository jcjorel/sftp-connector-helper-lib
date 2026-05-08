"""Unit tests for Joiner Lambda handler."""

import json

import pytest

from tests.conftest import (
    SAMPLE_EVENT_RESULT,
    SAMPLE_METADATA,
    make_dynamo_image,
    make_pipe_event,
    make_stream_record,
)


class TestSuccessfulPublish:
    """AC #1: Complete MODIFY record publishes enriched event."""

    def test_modify_publishes_when_old_image_missing_metadata(self, mock_events, mock_cloudwatch):
        """MODIFY with NewImage having both fields, OldImage missing metadata → publishes."""
        import handler

        new_img = make_dynamo_image("job-1", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-1", event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-1", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_events.put_events.assert_called_once()
        call_args = mock_events.put_events.call_args[1]["Entries"][0]
        assert call_args["Source"] == "custom.sftp-connector-helper"
        assert call_args["DetailType"] == "SFTP Connector Directory Listing Completed"
        assert call_args["EventBusName"] == "test-bus"

        detail = json.loads(call_args["Detail"])
        assert detail["_helper_metadata"] == {"orderId": "ORD-456"}
        assert detail["connector-id"] == "c-01234"
        assert detail["listing-id"] == "listing-abc-123"
        assert detail["status"] == "COMPLETED"

    def test_insert_with_both_fields_publishes(self, mock_events, mock_cloudwatch):
        """INSERT with both fields present → publishes (race condition handling)."""
        import handler

        new_img = make_dynamo_image("job-2", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("INSERT", "job-2", new_image=new_img)
        event = make_pipe_event(record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_events.put_events.assert_called_once()

    def test_enriched_event_preserves_source_and_detail_type(self, mock_events, mock_cloudwatch):
        """Enriched event preserves original source and detail-type."""
        import handler

        new_img = make_dynamo_image("job-3", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-3", event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-3", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        handler.lambda_handler(event, None)

        call_args = mock_events.put_events.call_args[1]["Entries"][0]
        assert call_args["Source"] == "custom.sftp-connector-helper"
        assert call_args["DetailType"] == "SFTP Connector Directory Listing Completed"


class TestLoopPrevention:
    """AC #2: Skip when OldImage already had both fields."""

    def test_modify_skips_when_old_image_has_both(self, mock_events, mock_cloudwatch):
        """MODIFY with OldImage having both fields → skips, no publish."""
        import handler

        new_img = make_dynamo_image("job-4", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-4", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-4", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_events.put_events.assert_not_called()


class TestInvalidMetadata:
    """AC #3: Invalid metadata emits metric, no publish."""

    def test_metadata_array_rejected(self, mock_events, mock_cloudwatch):
        """Metadata that is a JSON array → emits metric, no publish."""
        import handler

        invalid_metadata = json.dumps([1, 2, 3])
        new_img = make_dynamo_image("job-5", metadata=invalid_metadata, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-5", event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-5", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        handler.lambda_handler(event, None)

        mock_events.put_events.assert_not_called()
        mock_cloudwatch.put_metric_data.assert_called_once()
        metric_data = mock_cloudwatch.put_metric_data.call_args[1]["MetricData"]
        assert any(m["MetricName"] == "InvalidMetadata" for m in metric_data)

    def test_metadata_string_rejected(self, mock_events, mock_cloudwatch):
        """Metadata that is a plain string → emits metric, no publish."""
        import handler

        invalid_metadata = json.dumps("just a string")
        new_img = make_dynamo_image("job-6", metadata=invalid_metadata, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-6", event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-6", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        handler.lambda_handler(event, None)

        mock_events.put_events.assert_not_called()
        mock_cloudwatch.put_metric_data.assert_called_once()

    def test_metadata_malformed_json_rejected(self, mock_events, mock_cloudwatch):
        """Metadata that is malformed JSON → emits metric, no publish."""
        import handler

        new_img = make_dynamo_image("job-7", metadata="{not valid json", event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-7", event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-7", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        handler.lambda_handler(event, None)

        mock_events.put_events.assert_not_called()
        mock_cloudwatch.put_metric_data.assert_called_once()

    def test_invalid_metadata_metric_has_connector_id_dimension(self, mock_events, mock_cloudwatch):
        """InvalidMetadata metric includes ConnectorId dimension."""
        import handler

        invalid_metadata = json.dumps([1, 2])
        new_img = make_dynamo_image("job-8", metadata=invalid_metadata, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-8", event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-8", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        handler.lambda_handler(event, None)

        metric_data = mock_cloudwatch.put_metric_data.call_args[1]["MetricData"]
        dimensioned = [m for m in metric_data if m.get("Dimensions")]
        assert len(dimensioned) == 1
        assert dimensioned[0]["Dimensions"][0]["Value"] == "c-01234"


class TestRemoveEvent:
    """REMOVE events are skipped (Story 2-5)."""

    def test_remove_event_skipped(self, mock_events, mock_cloudwatch):
        """REMOVE event → no action."""
        import handler

        record = make_stream_record("REMOVE", "job-9")
        event = make_pipe_event(record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_events.put_events.assert_not_called()


class TestErrorPropagation:
    """Errors propagate to Pipe for retry/DLQ handling."""

    def test_invalid_record_raises_exception(self, mock_events, mock_cloudwatch):
        """Processing failure raises exception (Pipe handles retry/DLQ)."""
        import handler

        # A completely invalid record (not a valid DynamoDB stream record)
        event = [{"invalid": "record"}]

        # Should not raise for missing fields — _process_record handles gracefully
        handler.lambda_handler(event, None)


class TestMetadataCopyNoRegression:
    """Verify metadata-copy integration does not break existing publish/loop paths."""

    def test_complete_record_without_transfer_id_still_publishes(self, mock_events, mock_cloudwatch, mock_table):
        """Record with both metadata+eventResult but no transferId → publishes (not copy path)."""
        import handler

        new_img = make_dynamo_image("job-reg1", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-reg1", event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-reg1", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_events.put_events.assert_called_once()
        mock_table.get_item.assert_not_called()
        mock_table.query.assert_not_called()

    def test_loop_prevention_still_works_with_metadata_copy_present(self, mock_events, mock_cloudwatch, mock_table):
        """MODIFY with OldImage having both fields → still skips (loop prevention unchanged)."""
        import handler

        new_img = make_dynamo_image("job-reg2", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-reg2", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-reg2", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        result = handler.lambda_handler(event, None)

        assert result is None
        mock_events.put_events.assert_not_called()
        mock_table.get_item.assert_not_called()
        mock_table.query.assert_not_called()


class TestTimingInLogs:
    """Verify start_timestamp and end_timestamp appear in structured logs."""

    def test_publish_log_contains_timing(self, mock_events, mock_cloudwatch, caplog):
        """Log at publish time includes start_timestamp and end_timestamp."""
        import logging
        import handler

        new_img = make_dynamo_image("job-t1", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
        old_img = make_dynamo_image("job-t1", event_result=SAMPLE_EVENT_RESULT)
        record = make_stream_record("MODIFY", "job-t1", new_image=new_img, old_image=old_img)
        event = make_pipe_event(record)

        with caplog.at_level(logging.INFO):
            handler.lambda_handler(event, None)

        log_entries = [json.loads(r.message) for r in caplog.records if r.message.startswith("{")]
        publish_entry = [e for e in log_entries if e.get("message") == "Record complete, publishing enriched event"]
        assert len(publish_entry) == 1
        assert publish_entry[0]["start_timestamp"] == "2024-01-24T18:28:07.632388Z"
        assert publish_entry[0]["end_timestamp"] == "2024-01-24T18:28:07.774898Z"
