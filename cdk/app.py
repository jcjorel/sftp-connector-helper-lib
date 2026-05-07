import aws_cdk as cdk

from sftp_connector_helper.construct import SftpConnectorHelper

app = cdk.App()
stack = cdk.Stack(app, "SftpConnectorHelperStack")
SftpConnectorHelper(stack, "SftpConnectorHelper")
app.synth()
