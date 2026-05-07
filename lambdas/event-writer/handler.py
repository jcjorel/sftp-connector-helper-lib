"""Event Writer Lambda — captures SFTP Connector EventBridge events into DynamoDB."""

import json
import logging
import os
import time

import boto3

from field_mapping import get_job_id

logger = logging.getLogger()
logger.setLevel(logging.INFO)

TABLE_NAME = os.environ.get("TABLE_NAME", "sftp-connector-helper")

dynamodb = boto3.client("dynamodb")


def log_structured(level: str, message: str, **kwargs) -> None:
    """Emit structured JSON log line with mandatory fields."""
    entry = {"level": level, "message": message}
    entry.update(kwargs)
    logger.log(getattr(logging, level), json.dumps(entry))


def lambda_handler(event, context):
    """Process SQS records containing EventBridge events."""
    ttl_seconds = int(os.environ.get("TTL_SECONDS", "86400"))

    for record in event["Records"]:
        eb_event = json.loads(record["body"])
        detail_type = eb_event["detail-type"]
        detail = eb_event["detail"]
        connector_id = detail.get("connector-id", "unknown")

        job_id, operation, is_per_file = get_job_id(detail, detail_type)

        log_structured(
            "INFO",
            "Processing event",
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
            job_id=job_id,
            operation=operation,
            connector_id=connector_id,
        )
