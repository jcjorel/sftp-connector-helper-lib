# User Guide

## Java API Reference

### Builder

```java
import io.github.jcjorel.sftpconnectorhelper.*;

SftpConnectorHelper helper = SftpConnectorHelper.builder()
    .tableName("sftp-connector-helper")   // default
    .ttlDuration(Duration.ofHours(24))    // default
    .dynamoDbClient(customClient)         // optional
    .transferClient(customClient)         // optional
    .build();
```

The helper implements `AutoCloseable` — use try-with-resources or call `close()` to release SDK clients.

### Operations

All operations follow the same pattern: validate metadata (throws `IllegalArgumentException` if invalid), execute the SDK call, then write metadata to DynamoDB.

| Method | SDK Request Type | Returns |
|--------|-----------------|---------|
| `startFileTransfer(request, metadata)` | `StartFileTransferRequest` | `SftpOperationResult<StartFileTransferResponse>` |
| `startDirectoryListing(request, metadata)` | `StartDirectoryListingRequest` | `SftpOperationResult<StartDirectoryListingResponse>` |
| `startRemoteMove(request, metadata)` | `StartRemoteMoveRequest` | `SftpOperationResult<StartRemoteMoveResponse>` |
| `startRemoteDelete(request, metadata)` | `StartRemoteDeleteRequest` | `SftpOperationResult<StartRemoteDeleteResponse>` |

> **Note**: `startFileTransfer` uses a staggered TTL (base + 1 hour) to ensure the master record outlives per-file records during multi-file fan-out operations. Other operations use the standard TTL.

### Result Types

```java
sealed interface SftpOperationResult<T> {
    record Success<T>(T response) implements SftpOperationResult<T> {}
    record MetadataWriteFailed<T>(T response, String jobId, Exception cause) implements SftpOperationResult<T> {}
    record MetadataAlreadyExists<T>(T response, String jobId) implements SftpOperationResult<T> {}
}
```

- **Success** — SDK call + metadata write both succeeded
- **MetadataWriteFailed** — Transfer is in progress but metadata won't appear in enriched events
- **MetadataAlreadyExists** — Conditional write failed; metadata was already written for this jobId

### Metadata Format

Metadata must be a valid JSON **object** (not array, not primitive).

Constraints:
- Maximum size: **25,000 bytes** (25 KB). Exceeding this throws `IllegalArgumentException`.
- Maximum JSON nesting depth: **50 levels**.
- Must not be `null`, empty, an array, or a primitive.

```java
// Valid
helper.startFileTransfer(req, "{\"orderId\":\"ORD-001\"}");

// Invalid — will throw IllegalArgumentException
helper.startFileTransfer(req, "just a string");
helper.startFileTransfer(req, "[1,2,3]");
helper.startFileTransfer(req, "null");
```

### IAM Permissions Required by Caller

Your application's IAM role needs:

```json
{
  "Effect": "Allow",
  "Action": ["dynamodb:UpdateItem"],
  "Resource": "arn:aws:dynamodb:<region>:<account>:table/sftp-connector-helper"
},
{
  "Effect": "Allow",
  "Action": ["transfer:StartFileTransfer", "transfer:StartDirectoryListing", "transfer:StartRemoteMove", "transfer:StartRemoteDelete"],
  "Resource": "arn:aws:transfer:<region>:<account>:connector/<connector-id>"
}
```

For `DirectoryListingFilter`, add `s3:GetObject` on the listing output bucket.

> **Note**: If using `existing_table_arn` in the CDK construct, ensure the Joiner Lambda role has `dynamodb:UpdateItem` and `dynamodb:GetItem` on the table, plus `dynamodb:Query` on the `transferId-index` GSI. The CDK construct handles this automatically for new tables.

### Directory Listing Filter

Filter listing results by regex pattern directly from enriched events:

```java
DirectoryListingFilter filter = new DirectoryListingFilter(s3Client);
DirectoryListingResult result = filter.filter(enrichedEventJson, ".*\\.csv", null); // throws IOException
// result.files()     → List<Map<String, Object>> entries matching fileRegex
// result.paths()     → List<Map<String, Object>> entries matching pathRegex (null = include all)
// result.truncated() → "true"/"false" from the original S3 listing output
```

Parameters:
- `eventJson` — EventBridge event JSON (raw or enriched), must contain `detail.output-file-location.bucket` and `key`
- `fileRegex` — regex for filePath filtering: `null` = include all, `""` = exclude all, otherwise `Matcher.find()`
- `pathRegex` — regex for path filtering: `null` = include all, `""` = exclude all, otherwise `Matcher.find()`

Throws:
- `IOException` — if JSON parsing or S3 read fails
- `IllegalArgumentException` — if the event JSON does not contain `detail.output-file-location` with both `bucket` and `key` fields

## Consuming Enriched Events

### Event Format

Events are published to the dedicated bus with:
- **source**: `custom.sftp-connector-helper`
- **detail-type**: Same as the original Transfer Family event
- **detail**: Original Transfer Family event detail + `_helper_metadata` field

```json
{
  "source": "custom.sftp-connector-helper",
  "detail-type": "SFTP Connector File Send Completed",
  "detail": {
    "connector-id": "c-1234567890abcdef0",
    "transfer-id": "xfer-abc123",
    "status-code": "COMPLETED",
    "file-path": "/outbound/invoice-001.csv",
    "_helper_metadata": {
      "orderId": "ORD-001",
      "customer": "ACME"
    }
  }
}
```

> **Note**: The `detail-type` preserves the original Transfer Family value (e.g., `"SFTP Connector File Send Completed"`, `"SFTP Connector File Retrieve Completed"`, `"SFTP Connector Directory Listing Completed"`). Use prefix matching in your rules to catch both success and failure events.

### EventBridge Rule (CDK)

```python
events.Rule(self, "MyConsumerRule",
    event_bus=helper.event_bus,
    event_pattern=events.EventPattern(
        source=["custom.sftp-connector-helper"],
        detail_type=["SFTP Connector File Send Completed"],
    ),
    targets=[targets.LambdaFunction(my_lambda)],
)
```

### EventBridge Rule (Console/CLI)

```json
{
  "source": ["custom.sftp-connector-helper"],
  "detail-type": ["SFTP Connector File Send Completed"],
  "detail": {
    "status-code": ["COMPLETED"]
  }
}
```

## Operational Runbook

### Monitoring Dashboard

Key metrics to watch (namespace: `SftpConnectorHelper`):

| Metric | Emitted By | Alert Threshold | Action |
|--------|-----------|----------------|--------|
| `OrphanedRecords` | Joiner Lambda | > 0 | Investigate: app not writing metadata before Transfer Family events arrive. Note: `ConnectorId` dimension is `UNKNOWN` for metadata-only orphans (no event to extract from). |
| `InvalidMetadata` | Joiner Lambda | > 0 | Fix caller: metadata must be a JSON object |
| `UnknownOperationType` | Event Writer Lambda | > 0 | New Transfer Family event type? Update `field_mapping.py` |

AWS-managed metrics:
| Metric | Namespace | Alert Threshold |
|--------|-----------|----------------|
| Pipe IteratorAge | `AWS/Pipes` | > 60s for 3 min → Joiner stalled |
| Event Writer Errors | `AWS/Lambda` | > 0 |
| Joiner Errors | `AWS/Lambda` | > 0 |

### DLQ Inspection

Two DLQs exist:
1. **EventWriterDLQ** — Events that failed Lambda invocation after 2 retries (3 total attempts)
2. **PipeDLQ** — DynamoDB Stream records that failed Joiner processing after 3 retries (or 1-hour record age, whichever comes first)

Inspect with:
```bash
aws sqs receive-message --queue-url <dlq-url> --max-number-of-messages 10 --profile <profile>
```

### Orphan Alert Response

When you receive an SNS orphan alert:
1. Check if your application is failing to write metadata entirely (this is NOT a timing race — the system handles event-before-metadata ordering gracefully)
2. Verify the connector ID in the alert matches a connector you're tracking
3. If legitimate orphan: no action needed (TTL will clean up)
4. If systematic: your app may be crashing before calling the helper — check app logs and error handling

### Log Groups

| Log Group | Content |
|-----------|---------|
| `/aws/lambda/sftp-connector-helper-event-writer` | Event capture, field mapping |
| `/aws/lambda/sftp-connector-helper-joiner` | Correlation, publishing, orphan detection |

All logs are structured JSON with fields: `job_id`, `connector_id`, `operation`, `message`.

## Troubleshooting

### "Transfer succeeded but no enriched event appeared"

1. Check Event Writer DLQ — was the Transfer Family event captured?
2. Check DynamoDB — does the item have both `metadata` and `eventResult`?
3. Check Joiner logs — was the enriched event published?
4. Check your consumer rule — does the event pattern match?

### "MetadataAlreadyExists returned"

Your application called the helper twice with the same operation. The first call already wrote metadata. The second SDK call started a *new* transfer that won't have metadata correlation. The `response` field in `MetadataAlreadyExists` contains the response from the new (second) SDK call — you can still extract the transfer ID from it. Fix: don't retry the helper method; retry at a higher level.

### "MetadataWriteFailed returned"

DynamoDB write failed after the SDK call succeeded. The transfer is running but won't produce an enriched event. Common causes:
- DynamoDB throttling (unlikely with on-demand)
- IAM permission missing on caller role
- Network timeout

### "Orphan alerts firing constantly"

- Your app is too slow writing metadata — Transfer Family events arrive first
- You're using SFTP Connector without the helper library (events have no matching metadata)
- A connector ID is not covered by your application

### Events arriving on the bus but consumer not triggering

- Verify your rule is on the **dedicated bus** (`sftp-connector-helper-bus`), not the default bus
- Check the `detail-type` in your rule matches exactly (case-sensitive)
- Verify your consumer Lambda has permission to be invoked by EventBridge

## Cost Estimates

Approximate monthly cost at different scales (us-east-1 pricing):

| Operations/day | DynamoDB | Lambda | EventBridge | Pipes | Total |
|---------------|----------|--------|-------------|-------|-------|
| 100 | < $0.01 | < $0.01 | < $0.01 | < $0.01 | **< $0.05** |
| 1,000 | < $0.05 | < $0.01 | < $0.01 | < $0.01 | **< $0.10** |
| 10,000 | ~$0.50 | ~$0.10 | ~$0.10 | ~$0.10 | **~$1.00** |
| 100,000 | ~$5.00 | ~$1.00 | ~$1.00 | ~$1.00 | **~$8.00** |

Notes:
- DynamoDB cost includes writes from both helper + event writer + joiner reads + fan-out writes
- Fan-out operations (multi-file transfers) multiply per-file write costs
- EventBridge Pipes charges per invocation ($0.40/million)
- SNS orphan alerts are negligible unless you have systematic orphans
- DynamoDB on-demand pricing; no reserved capacity assumed
