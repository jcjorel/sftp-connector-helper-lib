"""Event Writer Lambda — captures SFTP Connector EventBridge events into DynamoDB."""

import json
import logging
import os
import time

import boto3

from field_mapping import UnknownOperationError, get_job_id
from log_util import log_structured

logger = logging.getLogger()
logger.setLevel(logging.INFO)

TABLE_NAME = os.environ.get("TABLE_NAME", "sftp-connector-helper")

dynamodb = boto3.client("dynamodb")
cloudwatch = boto3.client("cloudwatch")


def emit_unknown_operation_metric(connector_id: str) -> None:
    """Emit UnknownOperationType metric with and without ConnectorId dimension."""
    cloudwatch.put_metric_data(
        Namespace="SftpConnectorHelper",
        MetricData=[
            {
                "MetricName": "UnknownOperationType",
                "Dimensions": [{"Name": "ConnectorId", "Value": connector_id}],
                "Value": 1,
                "Unit": "Count",
            },
            {
                "MetricName": "UnknownOperationType",
                "Value": 1,
                "Unit": "Count",
            },
        ],
    )


def lambda_handler(event, context):
    """Process SQS records containing EventBridge events."""
    ttl_seconds = int(os.environ.get("TTL_SECONDS", "86400"))

    for record in event["Records"]:
        eb_event = json.loads(record["body"])
        detail_type = eb_event["detail-type"]
        detail = eb_event["detail"]
        connector_id = detail.get("connector-id", "unknown")

        try:
            job_id, operation, is_per_file = get_job_id(detail, detail_type)
        except UnknownOperationError:
            log_structured(
                "WARNING",
                "Unknown operation type, discarding event",
                detail=detail,
                job_id="unknown",
                operation=detail_type,
                connector_id=connector_id,
            )
            try:
                emit_unknown_operation_metric(connector_id)
            except Exception:
                log_structured(
                    "WARNING",
                    "Failed to emit UnknownOperationType metric",
                    connector_id=connector_id,
                )
            continue

        log_structured(
            "INFO",
            "Processing event",
            detail=detail,
            job_id=job_id,
            operation=operation,
            connector_id=connector_id,
        )

        ttl = int(time.time()) + ttl_seconds

        # Build UpdateItem params
        update_expr = "SET eventResult = :e, #t = :t"
        expr_attr_names = {"#t": "ttl"}
        expr_attr_values = {
            ":e": {"S": json.dumps(eb_event)},
            ":t": {"N": str(ttl)},
        }

        if is_per_file:
            update_expr += ", transferId = :tid"
            expr_attr_values[":tid"] = {"S": detail["transfer-id"]}

        dynamodb.update_item(
            TableName=TABLE_NAME,
            Key={"jobId": {"S": job_id}},
            UpdateExpression=update_expr,
            ExpressionAttributeNames=expr_attr_names,
            ExpressionAttributeValues=expr_attr_values,
        )

        log_structured(
            "INFO",
            "Event stored",
            detail=detail,
            job_id=job_id,
            operation=operation,
            connector_id=connector_id,
        )
