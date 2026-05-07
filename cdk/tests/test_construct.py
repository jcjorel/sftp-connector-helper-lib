import json
from pathlib import Path

import aws_cdk as cdk
from aws_cdk.assertions import Template, Match

from sftp_connector_helper.construct import SftpConnectorHelper, SftpConnectorHelperProps

SNAPSHOT_DIR = Path(__file__).parent / "snapshots"


class TestSnapshot:
    def test_default_snapshot(self, template_default):
        SNAPSHOT_DIR.mkdir(exist_ok=True)
        snapshot_file = SNAPSHOT_DIR / "default_template.json"
        rendered = template_default.to_json()

        if not snapshot_file.exists():
            snapshot_file.write_text(json.dumps(rendered, indent=2))

        expected = json.loads(snapshot_file.read_text())
        assert rendered == expected


class TestDynamoDBTable:
    def test_default_creates_table_with_correct_name(self, template_default):
        template_default.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "TableName": "sftp-connector-helper",
            },
        )

    def test_default_creates_table_with_correct_key_schema(self, template_default):
        template_default.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "KeySchema": [
                    {"AttributeName": "jobId", "KeyType": "HASH"},
                ],
            },
        )

    def test_default_creates_table_on_demand_billing(self, template_default):
        template_default.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "BillingMode": "PAY_PER_REQUEST",
            },
        )

    def test_default_creates_table_with_streams(self, template_default):
        template_default.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "StreamSpecification": {
                    "StreamViewType": "NEW_AND_OLD_IMAGES",
                },
            },
        )

    def test_default_creates_table_with_ttl(self, template_default):
        template_default.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "TimeToLiveSpecification": {
                    "AttributeName": "ttl",
                    "Enabled": True,
                },
            },
        )

    def test_default_creates_gsi(self, template_default):
        template_default.has_resource_properties(
            "AWS::DynamoDB::Table",
            {
                "GlobalSecondaryIndexes": [
                    {
                        "IndexName": "transferId-index",
                        "KeySchema": [
                            {"AttributeName": "transferId", "KeyType": "HASH"},
                        ],
                        "Projection": {"ProjectionType": "ALL"},
                    },
                ],
            },
        )

    def test_existing_table_arn_skips_creation(self, template_existing_table):
        template_existing_table.resource_count_is("AWS::DynamoDB::Table", 0)


class TestEventBridge:
    def test_default_creates_bus_with_correct_name(self, template_default):
        template_default.has_resource_properties(
            "AWS::Events::EventBus",
            {
                "Name": "sftp-connector-helper-bus",
            },
        )

    def test_existing_bus_arn_skips_creation(self, template_existing_bus):
        template_existing_bus.resource_count_is("AWS::Events::EventBus", 0)


class TestSNS:
    def test_default_creates_sns_topic(self, template_default):
        template_default.resource_count_is("AWS::SNS::Topic", 1)


class TestEventWriterPipeline:
    def test_event_writer_queue_has_fixed_name(self, template_default):
        template_default.has_resource_properties(
            "AWS::SQS::Queue",
            {"QueueName": "sftp-connector-helper-event-writer"},
        )

    def test_event_writer_queue_has_dlq(self, template_default):
        template_default.has_resource_properties(
            "AWS::SQS::Queue",
            {
                "QueueName": "sftp-connector-helper-event-writer",
                "RedrivePolicy": Match.object_like(
                    {"maxReceiveCount": 3}
                ),
            },
        )

    def test_eventbridge_rule_pattern(self, template_default):
        template_default.has_resource_properties(
            "AWS::Events::Rule",
            {
                "EventPattern": {
                    "source": ["aws.transfer"],
                    "detail-type": [{"prefix": "SFTP Connector"}],
                },
            },
        )

    def test_event_writer_lambda_environment(self, template_default):
        template_default.has_resource_properties(
            "AWS::Lambda::Function",
            {
                "Environment": {
                    "Variables": {
                        "TABLE_NAME": Match.any_value(),
                    },
                },
                "Handler": "handler.lambda_handler",
                "Runtime": "python3.12",
            },
        )

    def test_event_writer_lambda_iam_scoped_to_table(self, template_default):
        template_default.has_resource_properties(
            "AWS::IAM::Policy",
            {
                "PolicyDocument": {
                    "Statement": Match.array_with(
                        [
                            Match.object_like(
                                {
                                    "Action": "dynamodb:UpdateItem",
                                    "Effect": "Allow",
                                    "Resource": Match.any_value(),
                                }
                            ),
                        ]
                    ),
                },
            },
        )
        # Verify the resource is NOT "*" (must be scoped to table ARN)
        template_default.has_resource_properties(
            "AWS::IAM::Policy",
            {
                "PolicyDocument": {
                    "Statement": Match.not_(
                        Match.array_with(
                            [
                                Match.object_like(
                                    {
                                        "Action": "dynamodb:UpdateItem",
                                        "Resource": "*",
                                    }
                                ),
                            ]
                        )
                    ),
                },
            },
        )

    def test_event_source_mapping_connects_queue_to_lambda(self, template_default):
        template_default.resource_count_is(
            "AWS::Lambda::EventSourceMapping", 1
        )
