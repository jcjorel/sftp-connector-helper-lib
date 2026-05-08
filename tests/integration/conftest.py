"""Integration test fixtures for stack resource discovery and cleanup."""

import json
import os
import time
import uuid

import boto3
import pytest


@pytest.fixture(scope="session")
def stack_name():
    """Read TEST_STACK_NAME env var — fail fast if missing."""
    name = os.environ.get("TEST_STACK_NAME")
    if not name:
        pytest.fail("TEST_STACK_NAME environment variable is required")
    return name


@pytest.fixture(scope="session")
def stack_outputs(stack_name):
    """Discover stack resources via CloudFormation describe_stacks.

    Falls back to fixed resource names if no outputs are exported.
    """
    cfn = boto3.client("cloudformation")
    resp = cfn.describe_stacks(StackName=stack_name)
    stacks = resp["Stacks"]
    if not stacks:
        pytest.fail(f"Stack {stack_name} not found")

    outputs = {o["OutputKey"]: o["OutputValue"] for o in stacks[0].get("Outputs", [])}

    # Fall back to fixed names (construct uses hardcoded names)
    return {
        "table_name": outputs.get("TableName", "sftp-connector-helper"),
        "bus_name": outputs.get("EventBusName", "sftp-connector-helper-bus"),
    }


@pytest.fixture(scope="session")
def cfn_client():
    """Provide CloudFormation client."""
    return boto3.client("cloudformation")


@pytest.fixture(scope="session")
def lambda_client():
    """Provide Lambda client."""
    return boto3.client("lambda")


@pytest.fixture(scope="session")
def cloudwatch_client():
    """Provide CloudWatch client."""
    return boto3.client("cloudwatch")


@pytest.fixture(scope="session")
def sns_client():
    """Provide SNS client."""
    return boto3.client("sns")


@pytest.fixture(scope="session")
def joiner_function_name(cfn_client, stack_name):
    """Discover the Joiner Lambda function name from CloudFormation stack resources."""
    paginator = cfn_client.get_paginator("list_stack_resources")
    for page in paginator.paginate(StackName=stack_name):
        for resource in page["StackResourceSummaries"]:
            if resource["ResourceType"] == "AWS::Lambda::Function" and "Joiner" in resource["LogicalResourceId"]:
                return resource["PhysicalResourceId"]
    pytest.fail(f"Joiner Lambda not found in stack {stack_name}")


@pytest.fixture(scope="session")
def orphan_sns_topic_arn(cfn_client, stack_name):
    """Discover the orphan alert SNS topic ARN from CloudFormation stack resources."""
    paginator = cfn_client.get_paginator("list_stack_resources")
    for page in paginator.paginate(StackName=stack_name):
        for resource in page["StackResourceSummaries"]:
            if resource["ResourceType"] == "AWS::SNS::Topic" and "Orphan" in resource["LogicalResourceId"]:
                return resource["PhysicalResourceId"]
    pytest.fail(f"Orphan SNS topic not found in stack {stack_name}")


@pytest.fixture(scope="session")
def table(stack_outputs):
    """Provide DynamoDB table resource."""
    dynamodb = boto3.resource("dynamodb")
    return dynamodb.Table(stack_outputs["table_name"])


@pytest.fixture(scope="session")
def events_client():
    """Provide EventBridge client."""
    return boto3.client("events")


@pytest.fixture(scope="session")
def sqs_client():
    """Provide SQS client."""
    return boto3.client("sqs")


@pytest.fixture(scope="session")
def bus_name(stack_outputs):
    """Provide the dedicated event bus name."""
    return stack_outputs["bus_name"]


@pytest.fixture(scope="session")
def bus_arn(events_client, bus_name):
    """Resolve bus ARN from bus name."""
    resp = events_client.describe_event_bus(Name=bus_name)
    return resp["Arn"]


@pytest.fixture(scope="session")
def event_consumer(events_client, sqs_client, bus_name, bus_arn):
    """Create a temporary SQS queue + EventBridge rule to capture enriched events.

    Session-scoped: one consumer for all tests, filtered by source.
    """
    session_id = uuid.uuid4().hex[:8]
    queue_name = f"integ-test-{session_id}"
    rule_name = f"integ-test-rule-{session_id}"

    # Create temp SQS queue
    resp = sqs_client.create_queue(QueueName=queue_name)
    queue_url = resp["QueueUrl"]

    # Get queue ARN
    attrs = sqs_client.get_queue_attributes(
        QueueUrl=queue_url, AttributeNames=["QueueArn"]
    )
    queue_arn = attrs["Attributes"]["QueueArn"]

    # Create EventBridge rule on dedicated bus
    events_client.put_rule(
        Name=rule_name,
        EventBusName=bus_name,
        EventPattern=json.dumps({"source": ["aws.transfer"]}),
        State="ENABLED",
    )

    # Get rule ARN for SQS policy
    rule_resp = events_client.describe_rule(Name=rule_name, EventBusName=bus_name)
    rule_arn = rule_resp["Arn"]

    # Set SQS policy allowing this rule
    policy = json.dumps(
        {
            "Version": "2012-10-17",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Principal": {"Service": "events.amazonaws.com"},
                    "Action": "sqs:SendMessage",
                    "Resource": queue_arn,
                    "Condition": {"ArnEquals": {"aws:SourceArn": rule_arn}},
                }
            ],
        }
    )
    sqs_client.set_queue_attributes(
        QueueUrl=queue_url, Attributes={"Policy": policy}
    )

    # Add SQS queue as target
    events_client.put_targets(
        Rule=rule_name,
        EventBusName=bus_name,
        Targets=[{"Id": "test-queue", "Arn": queue_arn}],
    )

    yield {"queue_url": queue_url, "rule_name": rule_name}

    # Cleanup
    try:
        events_client.remove_targets(
            Rule=rule_name, EventBusName=bus_name, Ids=["test-queue"]
        )
    except Exception:
        pass
    try:
        events_client.delete_rule(Name=rule_name, EventBusName=bus_name)
    except Exception:
        pass
    try:
        sqs_client.delete_queue(QueueUrl=queue_url)
    except Exception:
        pass


def generate_job_id():
    """Generate a unique job ID (UUID4) for test isolation."""
    return str(uuid.uuid4())


@pytest.fixture
def job_id():
    """Provide a unique job ID per test."""
    return generate_job_id()


@pytest.fixture(autouse=True)
def cleanup_dynamodb(request, table):
    """Delete test records from DynamoDB after each test."""
    created_keys = []

    def track(job_id):
        created_keys.append(job_id)

    yield track

    for key in created_keys:
        try:
            table.delete_item(Key={"jobId": key})
        except Exception:
            pass


def poll_events(sqs_client, queue_url, expected_count=1, timeout=20, match_field=None, match_values=None):
    """Poll SQS queue for enriched events with timeout.

    Args:
        match_field: JSON path in event detail to filter on (e.g., "listing-id")
        match_values: Set/list of acceptable values for match_field

    Returns list of parsed event dicts matching the filter.
    """
    events = []
    deadline = time.time() + timeout

    while len(events) < expected_count and time.time() < deadline:
        resp = sqs_client.receive_message(
            QueueUrl=queue_url,
            MaxNumberOfMessages=10,
            WaitTimeSeconds=1,
        )
        for msg in resp.get("Messages", []):
            body = json.loads(msg["Body"])
            sqs_client.delete_message(
                QueueUrl=queue_url, ReceiptHandle=msg["ReceiptHandle"]
            )
            # Filter by match_field if specified
            if match_field and match_values is not None:
                detail = json.loads(body["detail"]) if isinstance(body.get("detail"), str) else body.get("detail", {})
                if detail.get(match_field) not in match_values:
                    continue
            events.append(body)

    return events
