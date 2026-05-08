"""Integration tests for orphan detection via Joiner Lambda.

Tests invoke the Joiner Lambda directly with synthetic REMOVE stream records
(not waiting for actual TTL expiry). Validates CloudWatch metric emission
and SNS notification publishing.
"""

import json
import time
import uuid
from datetime import datetime, timezone

import boto3
import pytest

from conftest import generate_job_id


# --- Helpers (Task 2) ---


def build_synthetic_remove_record(old_image_attrs: dict) -> dict:
    """Build a DynamoDB Stream REMOVE record in marshalled format.

    Args:
        old_image_attrs: Dict of attribute_name -> {"S": val} | {"N": val} typed attrs.
    """
    job_id = old_image_attrs.get("jobId", {}).get("S", "unknown")
    return {
        "eventName": "REMOVE",
        "dynamodb": {
            "Keys": {"jobId": {"S": job_id}},
            "OldImage": old_image_attrs,
        },
    }


def build_joiner_sqs_event(stream_records: list[dict]) -> dict:
    """Wrap stream records in the SQS event format the Joiner Lambda expects."""
    return {
        "Records": [
            {
                "messageId": f"test-msg-{i}",
                "body": json.dumps(record),
            }
            for i, record in enumerate(stream_records)
        ]
    }


# --- Fixtures ---


@pytest.fixture(scope="session")
def orphan_consumer(sns_client, sqs_client, orphan_sns_topic_arn):
    """Create a temporary SQS queue subscribed to the orphan SNS topic.

    Session-scoped: one consumer for all orphan detection tests.
    """
    session_id = uuid.uuid4().hex[:8]
    queue_name = f"orphan-test-{session_id}"

    # Create temp SQS queue
    resp = sqs_client.create_queue(QueueName=queue_name)
    queue_url = resp["QueueUrl"]

    attrs = sqs_client.get_queue_attributes(
        QueueUrl=queue_url, AttributeNames=["QueueArn"]
    )
    queue_arn = attrs["Attributes"]["QueueArn"]

    # Allow SNS to send to this queue
    policy = json.dumps({
        "Version": "2012-10-17",
        "Statement": [{
            "Effect": "Allow",
            "Principal": {"Service": "sns.amazonaws.com"},
            "Action": "sqs:SendMessage",
            "Resource": queue_arn,
            "Condition": {"ArnEquals": {"aws:SourceArn": orphan_sns_topic_arn}},
        }],
    })
    sqs_client.set_queue_attributes(QueueUrl=queue_url, Attributes={"Policy": policy})

    # Subscribe queue to orphan topic
    sub_resp = sns_client.subscribe(
        TopicArn=orphan_sns_topic_arn,
        Protocol="sqs",
        Endpoint=queue_arn,
        ReturnSubscriptionArn=True,
    )
    subscription_arn = sub_resp["SubscriptionArn"]

    # Brief pause for subscription propagation
    time.sleep(2)

    yield {"queue_url": queue_url, "subscription_arn": subscription_arn}

    # Cleanup
    try:
        sns_client.unsubscribe(SubscriptionArn=subscription_arn)
    except Exception:
        pass
    try:
        sqs_client.delete_queue(QueueUrl=queue_url)
    except Exception:
        pass


def poll_sns_messages(sqs_client, queue_url, expected_count=1, timeout=30):
    """Poll SQS queue for SNS notifications with timeout."""
    messages = []
    deadline = time.time() + timeout

    while len(messages) < expected_count and time.time() < deadline:
        resp = sqs_client.receive_message(
            QueueUrl=queue_url,
            MaxNumberOfMessages=10,
            WaitTimeSeconds=2,
        )
        for msg in resp.get("Messages", []):
            body = json.loads(msg["Body"])
            # SNS wraps the message in a Message field
            sns_message = json.loads(body["Message"])
            sqs_client.delete_message(
                QueueUrl=queue_url, ReceiptHandle=msg["ReceiptHandle"]
            )
            messages.append(sns_message)

    return messages


def poll_cloudwatch_metric(cloudwatch_client, start_time, metric_name="OrphanedRecords", timeout=120):
    """Poll CloudWatch for metric data with timeout."""
    deadline = time.time() + timeout

    while time.time() < deadline:
        resp = cloudwatch_client.get_metric_statistics(
            Namespace="SftpConnectorHelper",
            MetricName=metric_name,
            StartTime=start_time,
            EndTime=datetime.now(timezone.utc),
            Period=60,
            Statistics=["Sum"],
        )
        datapoints = resp.get("Datapoints", [])
        total = sum(dp["Sum"] for dp in datapoints)
        if total >= 1:
            return total
        time.sleep(5)

    return 0


# --- Tests ---


@pytest.mark.timeout(180)
class TestMetadataOnlyOrphan:
    """AC#1: Metadata-only orphan detection (Branch 3)."""

    def test_metadata_only_orphan_emits_metric_and_sns(
        self, lambda_client, joiner_function_name, cloudwatch_client,
        sqs_client, orphan_consumer, table, cleanup_dynamodb,
    ):
        """Invoke Joiner with REMOVE record (metadata present, no eventResult).

        Verifies OrphanedRecords metric emitted AND SNS notification published.
        """
        job_id = generate_job_id()
        cleanup_dynamodb(job_id)
        queue_url = orphan_consumer["queue_url"]

        # Write a DynamoDB record with metadata only to ensure GSI state
        table.update_item(
            Key={"jobId": job_id},
            UpdateExpression="SET metadata = :m, #t = :ttl",
            ExpressionAttributeNames={"#t": "ttl"},
            ExpressionAttributeValues={
                ":m": json.dumps({"businessKey": "orphan-test-metadata-only"}),
                ":ttl": int(time.time()) - 3600,
            },
        )

        # Build synthetic REMOVE record
        old_image_attrs = {
            "jobId": {"S": job_id},
            "metadata": {"S": json.dumps({"businessKey": "orphan-test-metadata-only"})},
            "ttl": {"N": str(int(time.time()) - 3600)},
        }
        stream_record = build_synthetic_remove_record(old_image_attrs)
        event = build_joiner_sqs_event([stream_record])

        # Record time before invocation for CloudWatch query
        start_time = datetime.now(timezone.utc)

        # Invoke Joiner Lambda
        resp = lambda_client.invoke(
            FunctionName=joiner_function_name,
            InvocationType="RequestResponse",
            Payload=json.dumps(event),
        )
        assert resp["StatusCode"] == 200
        payload = json.loads(resp["Payload"].read())
        assert payload.get("batchItemFailures", []) == []

        # Verify CloudWatch metric
        metric_sum = poll_cloudwatch_metric(cloudwatch_client, start_time)
        assert metric_sum >= 1, "OrphanedRecords metric not emitted"

        # Verify SNS notification
        messages = poll_sns_messages(sqs_client, queue_url)
        assert len(messages) >= 1, "No orphan SNS notification received"
        msg = messages[0]
        assert msg["job_id"] == job_id
        assert msg["orphan_type"] == "metadata-only"
        assert msg["connector_id"] == "UNKNOWN"


@pytest.mark.timeout(180)
class TestFileTransferMasterOrphan:
    """AC#2: FILE_TRANSFER master orphan detection (Branch 2)."""

    def test_master_no_files_orphan_emits_metric_and_sns(
        self, lambda_client, joiner_function_name, cloudwatch_client,
        sqs_client, orphan_consumer, table, cleanup_dynamodb,
    ):
        """Invoke Joiner with REMOVE for FILE_TRANSFER master with no per-file records.

        Verifies OrphanedRecords metric emitted AND SNS notification with orphan_type "master-no-files".
        """
        transfer_id = generate_job_id()
        cleanup_dynamodb(transfer_id)
        queue_url = orphan_consumer["queue_url"]

        # Ensure NO per-file records exist in GSI for this transferId
        # (fresh UUID guarantees this)

        # Build synthetic REMOVE record for FILE_TRANSFER master
        old_image_attrs = {
            "jobId": {"S": transfer_id},
            "metadata": {"S": json.dumps({"orderId": "ORD-orphan-test"})},
            "operationType": {"S": "FILE_TRANSFER"},
            "ttl": {"N": str(int(time.time()) - 3600)},
        }
        stream_record = build_synthetic_remove_record(old_image_attrs)
        event = build_joiner_sqs_event([stream_record])

        # Record time before invocation
        start_time = datetime.now(timezone.utc)

        # Invoke Joiner Lambda
        resp = lambda_client.invoke(
            FunctionName=joiner_function_name,
            InvocationType="RequestResponse",
            Payload=json.dumps(event),
        )
        assert resp["StatusCode"] == 200
        payload = json.loads(resp["Payload"].read())
        assert payload.get("batchItemFailures", []) == []

        # Verify CloudWatch metric
        metric_sum = poll_cloudwatch_metric(cloudwatch_client, start_time)
        assert metric_sum >= 1, "OrphanedRecords metric not emitted"

        # Verify SNS notification
        messages = poll_sns_messages(sqs_client, queue_url)
        assert len(messages) >= 1, "No orphan SNS notification received"
        msg = messages[0]
        assert msg["job_id"] == transfer_id
        assert msg["orphan_type"] == "master-no-files"
        assert msg["connector_id"] == "UNKNOWN"
