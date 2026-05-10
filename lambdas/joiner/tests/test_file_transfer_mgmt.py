"""Unit tests for file_transfer_mgmt module."""

import hashlib
import json
from unittest.mock import MagicMock, patch

import pytest

from file_transfer_mgmt import (
    _derive_batch_status,
    _derive_detail_type,
    _hash_key,
    process_file_completion,
    reconcile_existing_files,
    should_publish_individual_event,
)


class TestShouldPublishIndividualEvent:
    def test_none_master_record(self):
        assert should_publish_individual_event(None) is True

    def test_no_emission_mode(self):
        assert should_publish_individual_event({"jobId": "t-1"}) is True

    def test_individual_only(self):
        assert should_publish_individual_event({"emissionMode": "INDIVIDUAL_FILE_EVENTS_ONLY"}) is True

    def test_individual_and_whole(self):
        assert should_publish_individual_event({"emissionMode": "INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION"}) is True

    def test_whole_only_suppresses(self):
        assert should_publish_individual_event({"emissionMode": "WHOLE_TRANSFER_COMPLETION_ONLY"}) is False


class TestDeriveBatchStatus:
    def test_all_completed(self):
        assert _derive_batch_status({"f1": {"status": "COMPLETED"}, "f2": {"status": "COMPLETED"}}) == "ALL_COMPLETED"

    def test_all_failed(self):
        assert _derive_batch_status({"f1": {"status": "FAILED"}, "f2": {"status": "FAILED"}}) == "ALL_FAILED"

    def test_partial_failure(self):
        assert _derive_batch_status({"f1": {"status": "COMPLETED"}, "f2": {"status": "FAILED"}}) == "PARTIAL_FAILURE"


class TestDeriveDetailType:
    def test_send(self):
        assert _derive_detail_type("SEND") == "SFTP Connector Whole File Send Transfer Completed - CUSTOM"

    def test_retrieve(self):
        assert _derive_detail_type("RETRIEVE") == "SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM"


class TestHashKey:
    def test_deterministic(self):
        assert _hash_key("ft-1") == _hash_key("ft-1")

    def test_hex_output(self):
        result = _hash_key("ft-1")
        assert len(result) == 64
        assert all(c in "0123456789abcdef" for c in result)

    def test_slash_containing_id(self):
        """IDs with slashes produce valid DynamoDB-safe keys."""
        key = _hash_key("de72024b-690a-4352-b2c9-5121f58e62f3/6ISWwxcaQ8a2FeFsJ986Bg")
        assert "/" not in key
        assert "+" not in key


class TestProcessFileCompletion:
    def setup_method(self):
        self.table = MagicMock()
        self.events = MagicMock()
        self.events.put_events = MagicMock(return_value={"FailedEntryCount": 0, "Entries": [{}]})
        self.bus = "test-bus"
        self.master = {
            "jobId": "t-abc",
            "metadata": json.dumps({"orderId": "ORD-1"}),
            "emissionMode": "INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION",
            "expectedFiles": 3,
            "connectorId": "c-123",
            "transferDirection": "SEND",
        }

    def test_no_expected_files_skips(self):
        """No batch tracking if expectedFiles is absent."""
        master = {"jobId": "t-1", "metadata": "{}"}
        process_file_completion(self.table, self.events, self.bus, master, "ft-1", {})
        self.table.update_item.assert_not_called()

    def test_conditional_update_increments(self):
        """Successful conditional update uses hashed key and stores original fileTransferId."""
        self.table.update_item.return_value = {
            "Attributes": {"resolvedCount": 1, "batchEventPublished": False}
        }
        detail = {"status-code": "COMPLETED", "file-path": "/a.csv"}
        process_file_completion(self.table, self.events, self.bus, self.master, "ft-1", detail)
        self.table.update_item.assert_called_once()
        call_kwargs = self.table.update_item.call_args[1]
        # Verify hashed key is used in ExpressionAttributeNames
        assert call_kwargs["ExpressionAttributeNames"]["#ftId"] == _hash_key("ft-1")
        # Verify original fileTransferId is stored in the entry
        assert call_kwargs["ExpressionAttributeValues"][":entry"]["fileTransferId"] == "ft-1"
        # Not at expectedFiles yet, no batch event
        self.events.put_events.assert_not_called()

    def test_batch_event_published_when_all_resolved(self):
        """Batch event published when resolvedCount reaches expectedFiles."""
        self.master["expectedFiles"] = 1
        self.table.update_item.return_value = {
            "Attributes": {
                **self.master,
                "resolvedCount": 1,
                "batchEventPublished": False,
                "fileStatuses": {
                    "ft-1": {"status": "COMPLETED", "filePath": "/a.csv",
                             "eventDetail": json.dumps({"status-code": "COMPLETED", "file-path": "/a.csv"})}
                },
            }
        }
        detail = {"status-code": "COMPLETED", "file-path": "/a.csv"}
        process_file_completion(self.table, self.events, self.bus, self.master, "ft-1", detail)
        self.events.put_events.assert_called_once()
        call_args = self.events.put_events.call_args[1]["Entries"][0]
        assert "Whole File Send Transfer Completed" in call_args["DetailType"]

    def test_duplicate_file_transfer_id_skips_increment(self):
        """ConditionalCheckFailed does not increment resolvedCount."""
        exc = self.table.meta.client.exceptions.ConditionalCheckFailedException
        self.table.update_item.side_effect = exc({}, "ConditionalCheckFailedException")
        self.table.get_item.return_value = {
            "Item": {"resolvedCount": 2, "batchEventPublished": False, "jobId": "t-abc",
                     "emissionMode": "INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION", "expectedFiles": 3}
        }
        detail = {"status-code": "COMPLETED", "file-path": "/a.csv"}
        process_file_completion(self.table, self.events, self.bus, self.master, "ft-1", detail)
        # No batch event since resolvedCount(2) != expectedFiles(3)
        self.events.put_events.assert_not_called()

    def test_batch_not_published_if_already_published(self):
        """batchEventPublished=True prevents re-publication."""
        self.master["expectedFiles"] = 1
        self.table.update_item.return_value = {
            "Attributes": {"resolvedCount": 1, "batchEventPublished": True}
        }
        detail = {"status-code": "COMPLETED", "file-path": "/a.csv"}
        process_file_completion(self.table, self.events, self.bus, self.master, "ft-1", detail)
        self.events.put_events.assert_not_called()

    def test_individual_only_mode_skips_batch(self):
        """INDIVIDUAL_FILE_EVENTS_ONLY mode never publishes batch event."""
        self.master["emissionMode"] = "INDIVIDUAL_FILE_EVENTS_ONLY"
        self.master["expectedFiles"] = 1
        self.table.update_item.return_value = {
            "Attributes": {"resolvedCount": 1, "batchEventPublished": False}
        }
        detail = {"status-code": "COMPLETED", "file-path": "/a.csv"}
        process_file_completion(self.table, self.events, self.bus, self.master, "ft-1", detail)
        self.events.put_events.assert_not_called()

    def test_slash_containing_file_transfer_id(self):
        """file-transfer-id with slashes is hashed for DynamoDB safety."""
        self.table.update_item.return_value = {
            "Attributes": {"resolvedCount": 1, "batchEventPublished": False}
        }
        ft_id = "de72024b-690a-4352-b2c9-5121f58e62f3/6ISWwxcaQ8a2FeFsJ986Bg"
        detail = {"status-code": "COMPLETED", "file-path": "/a.csv"}
        process_file_completion(self.table, self.events, self.bus, self.master, ft_id, detail)
        call_kwargs = self.table.update_item.call_args[1]
        hashed = call_kwargs["ExpressionAttributeNames"]["#ftId"]
        assert "/" not in hashed
        assert call_kwargs["ExpressionAttributeValues"][":entry"]["fileTransferId"] == ft_id


class TestReconcileExistingFiles:
    def setup_method(self):
        self.table = MagicMock()
        self.events = MagicMock()
        self.events.put_events = MagicMock(return_value={"FailedEntryCount": 0, "Entries": [{}]})
        self.bus = "test-bus"
        self.master = {
            "jobId": "t-abc",
            "metadata": json.dumps({"orderId": "ORD-1"}),
            "emissionMode": "INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION",
            "expectedFiles": 2,
            "connectorId": "c-123",
            "transferDirection": "SEND",
        }

    def test_no_expected_files_skips(self):
        master = {"jobId": "t-1", "metadata": "{}"}
        reconcile_existing_files(self.table, self.events, self.bus, "t-1", master, [])
        self.table.update_item.assert_not_called()

    def test_reconciles_per_file_items(self):
        """Reconciles per-file records that arrived before master."""
        event_detail = {"file-transfer-id": "ft-1", "status-code": "COMPLETED", "file-path": "/a.csv"}
        per_file_items = [
            {"jobId": "ft-1", "eventResult": json.dumps({"detail": event_detail})},
        ]
        # After reconciliation, get_item for batch check
        self.table.get_item.return_value = {
            "Item": {**self.master, "resolvedCount": 1, "batchEventPublished": False,
                     "fileStatuses": {"ft-1": {"status": "COMPLETED", "filePath": "/a.csv",
                                               "eventDetail": json.dumps(event_detail)}}}
        }
        reconcile_existing_files(self.table, self.events, self.bus, "t-abc", self.master, per_file_items)
        # Should have called update_item for batch tracking
        self.table.update_item.assert_called()

    def test_publishes_batch_when_all_resolved(self):
        """Batch event published when all files reconciled."""
        self.master["expectedFiles"] = 1
        event_detail = {"file-transfer-id": "ft-1", "status-code": "COMPLETED", "file-path": "/a.csv"}
        per_file_items = [
            {"jobId": "ft-1", "eventResult": json.dumps({"detail": event_detail})},
        ]
        self.table.get_item.return_value = {
            "Item": {**self.master, "resolvedCount": 1, "batchEventPublished": False,
                     "fileStatuses": {"ft-1": {"status": "COMPLETED", "filePath": "/a.csv",
                                               "eventDetail": json.dumps(event_detail)}}}
        }
        reconcile_existing_files(self.table, self.events, self.bus, "t-abc", self.master, per_file_items)
        self.events.put_events.assert_called()
