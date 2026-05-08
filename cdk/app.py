import os
import aws_cdk as cdk

from sftp_connector_helper.construct import SftpConnectorHelper, SftpConnectorHelperProps

app = cdk.App()
stack = cdk.Stack(app, "SftpConnectorHelperStack",
    env=cdk.Environment(
        account=os.environ.get("CDK_DEFAULT_ACCOUNT"),
        region=os.environ.get("CDK_DEFAULT_REGION"),
    )
)

existing_table_arn = app.node.try_get_context("existing_table_arn")
existing_table_stream_arn = app.node.try_get_context("existing_table_stream_arn")
props = SftpConnectorHelperProps(
    existing_table_arn=existing_table_arn,
    existing_table_stream_arn=existing_table_stream_arn,
) if existing_table_arn else None

SftpConnectorHelper(stack, "SftpConnectorHelper", props)
app.synth()
