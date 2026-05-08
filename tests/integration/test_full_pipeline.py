"""Integration tests for the full correlation pipeline.

Tests write directly to DynamoDB (simulating Java library + Event Writer),
then poll for enriched events on the dedicated EventBridge bus via a
temporary SQS queue consumer.
"""

import json
import time

import pytest

from conftest import generate_job_id, poll_events


@pytest.mark.timeout(30)
class TestSimpleOperationCorrelation:
    """Test simple operation (e.g., DirectoryListing) correlation."""

    def test_metadata_correlation_produces_enriched_event(
        self, table, sqs_client, event_consumer, job_id, cleanup_dynamodb
    ):
        """AC#2: Write metadata + eventResult → enriched event with _helper_metadata."""
        cleanup_dynamodb(job_id)
        queue_url = event_consumer["queue_url"]
        metadata = {"businessKey": "test-value-simple"}

        # Metadata write (simulates Java library)
        table.update_item(
            Key={"jobId": job_id},
            UpdateExpression="SET metadata = :m, #t = :ttl",
            ExpressionAttributeNames={"#t": "ttl"},
            ExpressionAttributeValues={
                ":m": json.dumps(metadata),
                ":ttl": int(time.time()) + 86400,
            },
        )

        # Event write (simulates Event Writer)
        table.update_item(
            Key={"jobId": job_id},
            UpdateExpression="SET eventResult = :e, #t = :ttl",
            ExpressionAttributeNames={"#t": "ttl"},
            ExpressionAttributeValues={
                ":e": json.dumps(
                    {
                        "source": "aws.transfer",
                        "detail-type": "SFTP Connector Directory Listing Completed",
                        "detail": {
                            "listing-id": job_id,
                            "connector-id": "c-test01234567890ab",
                            "status-code": "COMPLETED",
                        },
                    }
                ),
                ":ttl": int(time.time()) + 86400,
            },
        )

        # Poll for enriched event
        events = poll_events(sqs_client, queue_url, expected_count=1, timeout=20,
                             match_field="listing-id", match_values={job_id})

        assert 1 <= len(events) <= 2, f"Expected 1 enriched event (at-most-once duplicate), got {len(events)}"
        event = events[0]
        assert event["source"] == "aws.transfer"
        assert event["detail-type"] == "SFTP Connector Directory Listing Completed"

        detail = json.loads(event["detail"]) if isinstance(event["detail"], str) else event["detail"]
        assert detail["_helper_metadata"] == metadata
        assert detail["listing-id"] == job_id
        assert detail["connector-id"] == "c-test01234567890ab"
        assert detail["status-code"] == "COMPLETED"

    def test_aws_transfer_source_accepted_on_custom_bus(
        self, events_client, bus_name
    ):
        """AC#4: PutEvents with source aws.transfer succeeds on custom bus."""
        resp = events_client.put_events(
            Entries=[
                {
                    "Source": "aws.transfer",
                    "DetailType": "Test Event",
                    "EventBusName": bus_name,
                    "Detail": json.dumps({"test": True}),
                }
            ]
        )
        assert resp["FailedEntryCount"] == 0


@pytest.mark.timeout(45)
class TestFanOutCorrelation:
    """Test StartFileTransfer fan-out with N=3 files."""

    def test_fan_out_produces_n_enriched_events(
        self, table, sqs_client, event_consumer, cleanup_dynamodb
    ):
        """AC#3: Master metadata + 3 per-file records → 3 enriched events with master metadata."""
        transfer_id = generate_job_id()
        file_ids = [generate_job_id() for _ in range(3)]
        queue_url = event_consumer["queue_url"]
        metadata = {"orderId": "ORD-123"}

        cleanup_dynamodb(transfer_id)
        for fid in file_ids:
            cleanup_dynamodb(fid)

        # Write master metadata record
        table.update_item(
            Key={"jobId": transfer_id},
            UpdateExpression="SET metadata = :m, operationType = :op, #t = :ttl",
            ExpressionAttributeNames={"#t": "ttl"},
            ExpressionAttributeValues={
                ":m": json.dumps(metadata),
                ":op": "FILE_TRANSFER",
                ":ttl": int(time.time()) + 86400 + 3600,
            },
        )

        # Write per-file event records
        for fid in file_ids:
            table.update_item(
                Key={"jobId": fid},
                UpdateExpression="SET eventResult = :e, transferId = :tid, #t = :ttl",
                ExpressionAttributeNames={"#t": "ttl"},
                ExpressionAttributeValues={
                    ":e": json.dumps(
                        {
                            "source": "aws.transfer",
                            "detail-type": "SFTP Connector File Send Completed",
                            "detail": {
                                "file-transfer-id": fid,
                                "transfer-id": transfer_id,
                                "connector-id": "c-test01234567890ab",
                                "status-code": "COMPLETED",
                            },
                        }
                    ),
                    ":tid": transfer_id,
                    ":ttl": int(time.time()) + 86400,
                },
            )
            time.sleep(0.5)  # Stagger writes so DynamoDB Streams delivers separate change events

        # Poll for 3 enriched events
        events = poll_events(sqs_client, queue_url, expected_count=3, timeout=30,
                             match_field="file-transfer-id", match_values=set(file_ids))

        assert 3 <= len(events) <= 4, f"Expected 3 enriched events (at-most-once duplicate), got {len(events)}"

        for event in events:
            assert event["source"] == "aws.transfer"
            assert event["detail-type"] == "SFTP Connector File Send Completed"

            detail = (
                json.loads(event["detail"])
                if isinstance(event["detail"], str)
                else event["detail"]
            )
            assert detail["_helper_metadata"] == metadata
            assert detail["connector-id"] == "c-test01234567890ab"
            assert detail["status-code"] == "COMPLETED"
            assert detail["file-transfer-id"] in file_ids
