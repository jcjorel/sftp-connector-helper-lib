from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import aws_cdk as cdk
from aws_cdk import (
    aws_cloudwatch as cloudwatch,
    aws_cloudwatch_actions as cw_actions,
    aws_dynamodb as dynamodb,
    aws_events as events,
    aws_events_targets as targets,
    aws_iam as iam,
    aws_lambda as _lambda,
    aws_logs as logs,
    aws_pipes as pipes,
    aws_sns as sns,
    aws_sqs as sqs,
)
from constructs import Construct


@dataclass
class SftpConnectorHelperProps:
    """Configuration properties for the SftpConnectorHelper CDK construct."""

    existing_table_arn: Optional[str] = None
    existing_table_stream_arn: Optional[str] = None
    existing_bus_arn: Optional[str] = None
    ttl_duration: cdk.Duration = cdk.Duration.days(1)
    event_writer_memory: int = 256
    event_writer_timeout: cdk.Duration = cdk.Duration.seconds(30)
    joiner_memory: int = 256
    joiner_timeout: cdk.Duration = cdk.Duration.seconds(30)
    event_bus_log_level: Optional[str] = "INFO"


class SftpConnectorHelper(Construct):
    """CDK construct that deploys the SFTP Connector Helper framework.

    Optimized for lowest latency:
    - Event Writer: EventBridge rule → Lambda (direct invocation)
    - Joiner: DynamoDB Streams → EventBridge Pipe (batch_size=1) → Lambda (direct invocation)
    """

    def __init__(
        self,
        scope: Construct,
        id: str,
        props: Optional[SftpConnectorHelperProps] = None,
    ) -> None:
        super().__init__(scope, id)

        props = props or SftpConnectorHelperProps()

        if bool(props.existing_table_arn) != bool(props.existing_table_stream_arn):
            raise ValueError(
                "existing_table_arn and existing_table_stream_arn must both be provided or both be None"
            )

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

        # EventBridge Bus Logging
        if props.event_bus_log_level and props.event_bus_log_level != "OFF" and not props.existing_bus_arn:
            cfn_bus = self._event_bus.node.default_child
            cfn_bus.add_property_override("LogConfig", {
                "Level": props.event_bus_log_level,
                "IncludeDetail": "FULL",
            })

        # SNS Topic for orphan alerts
        self._orphan_topic = sns.Topic(self, "OrphanAlertTopic")

        # Event Writer Pipeline — EventBridge → Lambda (direct, no SQS)
        event_writer_dlq = sqs.Queue(self, "EventWriterDLQ")

        event_writer_log_group = logs.LogGroup(
            self,
            "EventWriterLogGroup",
            log_group_name="/aws/lambda/sftp-connector-helper-event-writer",
            retention=logs.RetentionDays.ONE_WEEK,
            removal_policy=cdk.RemovalPolicy.DESTROY,
        )

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
                "TTL_SECONDS": str(int(props.ttl_duration.to_seconds())),
            },
            memory_size=props.event_writer_memory,
            timeout=props.event_writer_timeout,
            log_group=event_writer_log_group,
        )

        event_writer_lambda.add_to_role_policy(
            iam.PolicyStatement(
                actions=["dynamodb:UpdateItem"],
                resources=[self._table.table_arn],
            )
        )

        rule = events.Rule(
            self,
            "SftpConnectorEventRule",
            rule_name="sftp-connector-helper-event-capture",
            event_pattern=events.EventPattern(
                source=["aws.transfer"],
            ),
        )
        cfn_rule = rule.node.default_child
        cfn_rule.add_property_override(
            "EventPattern",
            {
                "source": ["aws.transfer"],
                "detail-type": [{"prefix": "SFTP Connector"}],
            },
        )
        rule.add_target(targets.LambdaFunction(
            event_writer_lambda,
            dead_letter_queue=event_writer_dlq,
            retry_attempts=2,
        ))

        # Joiner Pipeline — DynamoDB Streams → Pipe (batch_size=1) → Lambda (direct)
        stream_arn = props.existing_table_stream_arn or self._table.table_stream_arn

        pipe_dlq = sqs.Queue(
            self,
            "PipeDLQ",
            queue_name="sftp-connector-helper-pipe-dlq",
        )

        joiner_log_group = logs.LogGroup(
            self,
            "JoinerLogGroup",
            log_group_name="/aws/lambda/sftp-connector-helper-joiner",
            retention=logs.RetentionDays.ONE_WEEK,
            removal_policy=cdk.RemovalPolicy.DESTROY,
        )

        joiner_lambda = _lambda.Function(
            self,
            "JoinerFunction",
            runtime=_lambda.Runtime.PYTHON_3_12,
            handler="handler.lambda_handler",
            code=_lambda.Code.from_asset(
                str(Path(__file__).parent / "../../lambdas/joiner/dist")
            ),
            environment={
                "TABLE_NAME": self._table.table_name,
                "EVENT_BUS_NAME": self._event_bus.event_bus_name,
                "SNS_TOPIC_ARN": self._orphan_topic.topic_arn,
                "TTL_SECONDS": str(int(props.ttl_duration.to_seconds())),
            },
            memory_size=props.joiner_memory,
            timeout=props.joiner_timeout,
            log_group=joiner_log_group,
        )

        joiner_lambda.add_to_role_policy(
            iam.PolicyStatement(
                actions=["dynamodb:UpdateItem", "dynamodb:GetItem"],
                resources=[self._table.table_arn],
            )
        )
        joiner_lambda.add_to_role_policy(
            iam.PolicyStatement(
                actions=["dynamodb:Query"],
                resources=[f"{self._table.table_arn}/index/transferId-index"],
            )
        )
        self._event_bus.grant_put_events_to(joiner_lambda)
        joiner_lambda.add_to_role_policy(
            iam.PolicyStatement(
                actions=["cloudwatch:PutMetricData"],
                resources=["*"],
                conditions={"StringEquals": {"cloudwatch:namespace": "SftpConnectorHelper"}},
            )
        )
        self._orphan_topic.grant_publish(joiner_lambda)

        # Pipe: DynamoDB Streams → Joiner Lambda (direct invocation, batch_size=1)
        pipe_role = iam.Role(
            self,
            "PipeRole",
            assumed_by=iam.ServicePrincipal("pipes.amazonaws.com"),
        )
        pipe_role.add_to_policy(
            iam.PolicyStatement(
                actions=[
                    "dynamodb:DescribeStream",
                    "dynamodb:GetRecords",
                    "dynamodb:GetShardIterator",
                    "dynamodb:ListStreams",
                ],
                resources=[stream_arn],
            )
        )
        pipe_role.add_to_policy(
            iam.PolicyStatement(
                actions=["lambda:InvokeFunction"],
                resources=[joiner_lambda.function_arn],
            )
        )
        pipe_dlq.grant_send_messages(pipe_role)

        pipe = pipes.CfnPipe(
            self,
            "StreamToJoinerPipe",
            role_arn=pipe_role.role_arn,
            source=stream_arn,
            source_parameters=pipes.CfnPipe.PipeSourceParametersProperty(
                dynamo_db_stream_parameters=pipes.CfnPipe.PipeSourceDynamoDBStreamParametersProperty(
                    starting_position="LATEST",
                    batch_size=1,
                    maximum_batching_window_in_seconds=0,
                    maximum_retry_attempts=3,
                    maximum_record_age_in_seconds=3600,
                    dead_letter_config=pipes.CfnPipe.DeadLetterConfigProperty(
                        arn=pipe_dlq.queue_arn,
                    ),
                ),
            ),
            target=joiner_lambda.function_arn,
            target_parameters=pipes.CfnPipe.PipeTargetParametersProperty(
                lambda_function_parameters=pipes.CfnPipe.PipeTargetLambdaFunctionParametersProperty(
                    invocation_type="REQUEST_RESPONSE",
                ),
            ),
        )

        pipe.node.add_dependency(pipe_role)

        # CloudWatch Alarm on Pipe IteratorAge
        pipe_iterator_age_alarm = cloudwatch.Alarm(
            self,
            "PipeIteratorAgeAlarm",
            metric=cloudwatch.Metric(
                namespace="AWS/Pipes",
                metric_name="IteratorAge",
                dimensions_map={"PipeName": pipe.ref},
                statistic="Maximum",
                period=cdk.Duration.minutes(1),
            ),
            threshold=60000,
            evaluation_periods=3,
            datapoints_to_alarm=3,
            comparison_operator=cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
            alarm_description="EventBridge Pipe IteratorAge > 60s — Joiner pipeline may be stalled",
        )
        pipe_iterator_age_alarm.add_alarm_action(
            cw_actions.SnsAction(self._orphan_topic)
        )

    @property
    def table(self) -> dynamodb.ITable:
        return self._table

    @property
    def event_bus(self) -> events.IEventBus:
        return self._event_bus

    @property
    def orphan_topic(self) -> sns.ITopic:
        return self._orphan_topic
