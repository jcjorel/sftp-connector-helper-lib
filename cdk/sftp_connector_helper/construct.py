from dataclasses import dataclass
from typing import Optional

import aws_cdk as cdk
from aws_cdk import (
    aws_dynamodb as dynamodb,
    aws_events as events,
    aws_sns as sns,
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

    @property
    def table(self) -> dynamodb.ITable:
        return self._table

    @property
    def event_bus(self) -> events.IEventBus:
        return self._event_bus

    @property
    def orphan_topic(self) -> sns.ITopic:
        return self._orphan_topic
