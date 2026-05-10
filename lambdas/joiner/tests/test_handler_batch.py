"""Tests for stream loop guard and batch integration in handler."""

import json
from unittest.mock import MagicMock, patch

import pytest

from tests.conftest import make_dynamo_image, make_stream_record, SAMPLE_EVENT_RESULT, SAMPLE_METADATA


class TestStreamLoopGuard:
    """Test that batch-tracking MODIFY events on master records are skipped."""

    def test_batch_tracking_modify_skipped(self, mock_events, mock_table, mock_cloudwatch):
        """MODIFY with fileStatuses on master record (no eventResult) is skipped."""
        from handler import _process_record

        old_image = {"jobId": {"S": "t-abc"}, "metadata": {"S": SAMPLE_METADATA}}
        new_image = {
            "jobId": {"S": "t-abc"},
            "metadata": {"S": SAMPLE_METADATA},
            "fileStatuses": {"M": {"ft-1": {"M": {"status": {"S": "COMPLETED"}}}}},
            "resolvedCount": {"N": "1"},
        }
        record = make_stream_record("MODIFY", "t-abc", new_image=new_image, old_image=old_image)
        _process_record(record)

        mock_events.put_events.assert_not_called()
        mock_table.update_item.assert_not_called()

    def test_normal_modify_not_blocked(self, mock_events, mock_table, mock_cloudwatch):
        """MODIFY that completes a per-file record (adds eventResult) is NOT blocked."""
        from handler import _process_record

        old_image = make_dynamo_image("ft-1", transfer_id="t-abc")
        new_image = make_dynamo_image("ft-1", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT, transfer_id="t-abc")

        # Master record lookup for emission mode gating
        mock_table.get_item.return_value = {"Item": {"jobId": "t-abc", "metadata": SAMPLE_METADATA}}

        record = make_stream_record("MODIFY", "ft-1", new_image=new_image, old_image=old_image)
        _process_record(record)

        mock_events.put_events.assert_called_once()


class TestEmissionModeGating:
    """Test that emission mode controls individual event publication."""

    def test_whole_only_suppresses_individual_event(self, mock_events, mock_table, mock_cloudwatch):
        """WHOLE_TRANSFER_COMPLETION_ONLY suppresses individual event."""
        from handler import _process_record

        old_image = make_dynamo_image("ft-1", transfer_id="t-abc")
        new_image = make_dynamo_image("ft-1", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT, transfer_id="t-abc")

        mock_table.get_item.return_value = {
            "Item": {
                "jobId": "t-abc",
                "metadata": SAMPLE_METADATA,
                "emissionMode": "WHOLE_TRANSFER_COMPLETION_ONLY",
                "expectedFiles": 3,
            }
        }
        # process_file_completion conditional update
        mock_table.update_item.return_value = {"Attributes": {"resolvedCount": 1, "batchEventPublished": False}}

        record = make_stream_record("MODIFY", "ft-1", new_image=new_image, old_image=old_image)
        _process_record(record)

        # Individual event NOT published
        mock_events.put_events.assert_not_called()

    def test_legacy_record_publishes_normally(self, mock_events, mock_table, mock_cloudwatch):
        """Legacy record (no emissionMode) publishes individual event as before."""
        from handler import _process_record

        old_image = make_dynamo_image("ft-1", transfer_id="t-abc")
        new_image = make_dynamo_image("ft-1", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT, transfer_id="t-abc")

        # Master without emissionMode
        mock_table.get_item.return_value = {"Item": {"jobId": "t-abc", "metadata": SAMPLE_METADATA}}

        record = make_stream_record("MODIFY", "ft-1", new_image=new_image, old_image=old_image)
        _process_record(record)

        mock_events.put_events.assert_called_once()

    def test_no_transfer_id_publishes_normally(self, mock_events, mock_table, mock_cloudwatch):
        """Record without transferId (single-op like listing) publishes normally."""
        from handler import _process_record

        old_image = make_dynamo_image("job-1")
        new_image = make_dynamo_image("job-1", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)

        record = make_stream_record("MODIFY", "job-1", new_image=new_image, old_image=old_image)
        _process_record(record)

        mock_events.put_events.assert_called_once()
