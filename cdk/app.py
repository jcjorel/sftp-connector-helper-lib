import os
import aws_cdk as cdk

from sftp_connector_helper.construct import SftpConnectorHelper

app = cdk.App()
stack = cdk.Stack(app, "SftpConnectorHelperStack",
    env=cdk.Environment(
        account=os.environ.get("CDK_DEFAULT_ACCOUNT"),
        region=os.environ.get("CDK_DEFAULT_REGION"),
    )
)
SftpConnectorHelper(stack, "SftpConnectorHelper")
app.synth()
