import pytest
import aws_cdk as cdk
from aws_cdk.assertions import Template

from sftp_connector_helper.construct import SftpConnectorHelper, SftpConnectorHelperProps


@pytest.fixture
def template_default():
    app = cdk.App()
    stack = cdk.Stack(app, "TestStack")
    SftpConnectorHelper(stack, "Helper")
    return Template.from_stack(stack)


@pytest.fixture
def template_existing_table():
    app = cdk.App()
    stack = cdk.Stack(app, "TestStack")
    SftpConnectorHelper(
        stack,
        "Helper",
        props=SftpConnectorHelperProps(
            existing_table_arn="arn:aws:dynamodb:us-east-1:123456789012:table/my-table"
        ),
    )
    return Template.from_stack(stack)


@pytest.fixture
def template_existing_bus():
    app = cdk.App()
    stack = cdk.Stack(app, "TestStack")
    SftpConnectorHelper(
        stack,
        "Helper",
        props=SftpConnectorHelperProps(
            existing_bus_arn="arn:aws:events:us-east-1:123456789012:event-bus/my-bus"
        ),
    )
    return Template.from_stack(stack)


@pytest.fixture
def template_override():
    app = cdk.App()
    stack = cdk.Stack(app, "TestStack")
    SftpConnectorHelper(
        stack,
        "Helper",
        props=SftpConnectorHelperProps(
            ttl_duration=cdk.Duration.days(2),
            event_writer_memory=512,
            event_writer_timeout=cdk.Duration.seconds(60),
            joiner_memory=512,
            joiner_timeout=cdk.Duration.seconds(60),
        ),
    )
    return Template.from_stack(stack)
