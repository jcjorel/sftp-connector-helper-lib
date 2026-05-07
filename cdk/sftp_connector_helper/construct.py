from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import aws_cdk as cdk
from aws_cdk import (
    aws_dynamodb as dynamodb,
    aws_events as events,
    aws_events_targets as targets,
    aws_lambda as _lambda,
    aws_lambda_event_sources as lambda_event_sources,
    aws_sns as sns,
    aws_sqs as sqs,
)
from constructs import Construct


@dataclass
class SftpConnectorHelperProps:
    existing_table_arn: Optional[str] = None
    existing_bus_arn: Optional[str] = None
    ttl_duration: cdk.Duration = cdk.Duration.days(1)  # Passed to Joiner Lambda env var in Story 3-3; not used by CDK infra directly
    event_writer_memory: int = 256
    event_writer_timeout: cdk.Duration = cdk.Duration.seconds(30)
    joiner_memory: int = 256
    joiner_timeout: cdk.Duration = cdk.Duration.seconds(30)


class SftpConnectorHelper(Construct):
    def __init__(
        self,
        scope: Construct,
        id: str,
        props: Optional[SftpConnectorHelperProps] = None,
    ) -> None:
        super().__init__(scope, id)

        props = props or SftpConnectorHelperProps()

        # DynamoDB Table
        if props.existing_table_arn:
            self._table = dynamodb.Table.from_table_arn(
                self, "Table", props.existing_table_arn
            )
        else:
            self._table = dynamodb.Table(
                self,
                "Table",
                table_name="sftp-connector-helper",
                partition_key=dynamodb.Attribute(
                    name="jobId", type=dynamodb.AttributeType.STRING
                ),
                billing_mode=dynamodb.BillingMode.PAY_PER_REQUEST,
                stream=dynamodb.StreamViewType.NEW_AND_OLD_IMAGES,
                time_to_live_attribute="ttl",
                removal_policy=cdk.RemovalPolicy.RETAIN,
            )
            self._table.add_global_secondary_index(
                index_name="transferId-index",
                partition_key=dynamodb.Attribute(
                    name="transferId", type=dynamodb.AttributeType.STRING
                ),
            )

        # EventBridge Bus
        if props.existing_bus_arn:
            self._event_bus = events.EventBus.from_event_bus_arn(
                self, "EventBus", props.existing_bus_arn
            )
        else:
            self._event_bus = events.EventBus(
                self,
                "EventBus",
                event_bus_name="sftp-connector-helper-bus",
            )

        # SNS Topic for orphan alerts
        self._orphan_topic = sns.Topic(self, "OrphanAlertTopic")

        # Event Writer Pipeline
        event_writer_dlq = sqs.Queue(self, "EventWriterDLQ")

        event_writer_queue = sqs.Queue(
            self,
            "EventWriterQueue",
            queue_name="sftp-connector-helper-event-writer",
            dead_letter_queue=sqs.DeadLetterQueue(
                max_receive_count=3,
                queue=event_writer_dlq,
            ),
        )

        rule = events.Rule(
            self,
            "SftpConnectorEventRule",
            event_pattern=events.EventPattern(
                source=["aws.transfer"],
            ),
        )
        # Add prefix matching on detail-type via raw pattern override
        cfn_rule = rule.node.default_child
        cfn_rule.add_property_override(
            "EventPattern",
            {
                "source": ["aws.transfer"],
                "detail-type": [{"prefix": "SFTP Connector"}],
            },
        )
        rule.add_target(targets.SqsQueue(event_writer_queue))

        event_writer_lambda = _lambda.Function(
            self,
            "EventWriterFunction",
            runtime=_lambda.Runtime.PYTHON_3_12,
            handler="handler.lambda_handler",
            code=_lambda.Code.from_asset(
                str(Path(__file__).parent / "../../lambdas/event-writer/dist")
            ),
            environment={
                "TABLE_NAME": self._table.table_name,
            },
            memory_size=props.event_writer_memory,
            timeout=props.event_writer_timeout,
        )

        event_writer_lambda.add_event_source(
            lambda_event_sources.SqsEventSource(event_writer_queue)
        )

        self._table.grant(event_writer_lambda, "dynamodb:UpdateItem")

    @property
    def table(self) -> dynamodb.ITable:
        return self._table

    @property
    def event_bus(self) -> events.IEventBus:
        return self._event_bus

    @property
    def orphan_topic(self) -> sns.ITopic:
        return self._orphan_topic
