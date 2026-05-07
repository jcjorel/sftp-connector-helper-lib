"""Unit tests for metadata_copy module."""

import json
from unittest.mock import MagicMock, patch

import pytest

from tests.conftest import (
    SAMPLE_METADATA,
    SAMPLE_EVENT_RESULT,
    make_dynamo_image,
    make_sqs_event,
    make_stream_record,
)


@pytest.fixture
def mock_table():
    """Mock DynamoDB Table resource."""
    return MagicMock()


class TestCopyMetadataFromMaster:
    """Tests for copy_metadata_from_master function."""

    def test_copies_metadata_when_master_has_it(self, mock_table):
        """Master has metadata → copies to per-file record."""
        from metadata_copy import copy_metadata_from_master

        mock_table.get_item.return_value = {"Item": {"jobId": "master-1", "metadata": SAMPLE_METADATA}}

        copy_metadata_from_master(mock_table, "master-1", "perfile-1")

        mock_table.update_item.assert_called_once_with(
            Key={"jobId": "perfile-1"},
            UpdateExpression="SET metadata = :m",
            ExpressionAttributeValues={":m": SAMPLE_METADATA},
        )

    def test_no_update_when_master_has_no_metadata(self, mock_table):
        """Master has no metadata → no UpdateItem call."""
        from metadata_copy import copy_metadata_from_master

        mock_table.get_item.return_value = {"Item": {"jobId": "master-1"}}

        copy_metadata_from_master(mock_table, "master-1", "perfile-1")

        mock_table.update_item.assert_not_called()

    def test_no_update_when_master_not_found(self, mock_table):
        """Master record not found → no UpdateItem call."""
        from metadata_copy import copy_metadata_from_master

        mock_table.get_item.return_value = {}

        copy_metadata_from_master(mock_table, "master-1", "perfile-1")

        mock_table.update_item.assert_not_called()


class TestFanOutMetadata:
    """Tests for fan_out_metadata_to_per_file_records function."""

    def test_paginated_gsi_copies_to_eligible_records(self, mock_table):
        """2-page GSI response, copies to eligible records only."""
        from metadata_copy import fan_out_metadata_to_per_file_records

        mock_table.query.side_effect = [
            {
                "Items": [
                    {"jobId": "pf-1", "eventResult": "...", "transferId": "master-1"},
                    {"jobId": "pf-2", "eventResult": "...", "transferId": "master-1"},
                ],
                "LastEvaluatedKey": {"jobId": {"S": "pf-2"}},
            },
            {
                "Items": [
                    {"jobId": "pf-3", "eventResult": "...", "transferId": "master-1"},
                ],
            },
        ]

        fan_out_metadata_to_per_file_records(mock_table, "master-1", SAMPLE_METADATA)

        assert mock_table.update_item.call_count == 3
        assert mock_table.query.call_count == 2

    def test_skips_records_already_having_metadata(self, mock_table):
        """Per-file record already has metadata → skipped."""
        from metadata_copy import fan_out_metadata_to_per_file_records

        mock_table.query.return_value = {
            "Items": [
                {"jobId": "pf-1", "eventResult": "...", "metadata": "existing", "transferId": "master-1"},
                {"jobId": "pf-2", "eventResult": "...", "transferId": "master-1"},
            ],
        }

        fan_out_metadata_to_per_file_records(mock_table, "master-1", SAMPLE_METADATA)

        mock_table.update_item.assert_called_once_with(
            Key={"jobId": "pf-2"},
            UpdateExpression="SET metadata = :m",
            ExpressionAttributeValues={":m": SAMPLE_METADATA},
        )

    def test_skips_records_without_event_result(self, mock_table):
        """Per-file record without eventResult → skipped."""
        from metadata_copy import fan_out_metadata_to_per_file_records

        mock_table.query.return_value = {
            "Items": [
                {"jobId": "pf-1", "transferId": "master-1"},
            ],
        }

        fan_out_metadata_to_per_file_records(mock_table, "master-1", SAMPLE_METADATA)

        mock_table.update_item.assert_not_called()


class TestHandlerMetadataCopyRouting:
    """Tests for handler integration: metadata-copy routing."""

    def test_per_file_record_triggers_copy_not_publish(self, mock_events, mock_cloudwatch):
        """Per-file record (eventResult + transferId, no metadata) → copy path, no publish."""
        with patch("handler.table") as mock_tbl:
            mock_tbl.get_item.return_value = {"Item": {"jobId": "master-1", "metadata": SAMPLE_METADATA}}
            import handler

            new_img = make_dynamo_image("pf-1", event_result=SAMPLE_EVENT_RESULT, transfer_id="master-1")
            old_img = make_dynamo_image("pf-1")  # no metadata in old
            record = make_stream_record("MODIFY", "pf-1", new_image=new_img, old_image=old_img)
            event = make_sqs_event(record)

            result = handler.lambda_handler(event, None)

            assert result == {"batchItemFailures": []}
            mock_events.put_events.assert_not_called()
            mock_tbl.get_item.assert_called_once()

    def test_master_record_triggers_fanout_not_publish(self, mock_events, mock_cloudwatch):
        """Master record (metadata, no eventResult, no transferId) → fan-out path, no publish."""
        with patch("handler.table") as mock_tbl:
            mock_tbl.query.return_value = {"Items": []}
            import handler

            new_img = make_dynamo_image("master-1", metadata=SAMPLE_METADATA)
            record = make_stream_record("INSERT", "master-1", new_image=new_img)
            event = make_sqs_event(record)

            result = handler.lambda_handler(event, None)

            assert result == {"batchItemFailures": []}
            mock_events.put_events.assert_not_called()
            mock_tbl.query.assert_called_once()

    def test_modify_with_old_image_having_metadata_skips_copy(self, mock_events, mock_cloudwatch):
        """MODIFY with OldImage having metadata → loop prevention, no copy attempted."""
        with patch("handler.table") as mock_tbl:
            import handler

            new_img = make_dynamo_image("pf-1", metadata=SAMPLE_METADATA, event_result=SAMPLE_EVENT_RESULT)
            old_img = make_dynamo_image("pf-1", metadata=SAMPLE_METADATA)
            record = make_stream_record("MODIFY", "pf-1", new_image=new_img, old_image=old_img)
            event = make_sqs_event(record)

            result = handler.lambda_handler(event, None)

            assert result == {"batchItemFailures": []}
            mock_tbl.get_item.assert_not_called()
            mock_tbl.query.assert_not_called()
