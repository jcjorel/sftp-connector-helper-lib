# User Guide

## Java API Reference

> **New to the library?** Start with the [Getting Started tutorial](GETTING_STARTED.md) for hands-on scenarios before diving into the full API reference.

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
| `startFileTransfer(request, metadata)` | `StartFileTransferRequest` | `StartFileTransferResponse` |
| `startDirectoryListing(request, metadata)` | `StartDirectoryListingRequest` | `StartDirectoryListingResponse` |
| `startRemoteMove(request, metadata)` | `StartRemoteMoveRequest` | `StartRemoteMoveResponse` |
| `startRemoteDelete(request, metadata)` | `StartRemoteDeleteRequest` | `StartRemoteDeleteResponse` |

> **Note**: `startFileTransfer` handles multi-file fan-out automatically. See [Architecture — Fan-Out Path](ARCHITECTURE.md#fan-out-path-multi-file-transfer) for internal details.

### Batch Completion Events (FileTransferOptions)

For multi-file transfers, you can receive a single "all done" event instead of (or in addition to) individual per-file events:

```java
FileTransferOptions options = FileTransferOptions.builder()
    .emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY)
    .batchTimeout(Duration.ofMinutes(30))  // optional: default is 1 hour
    .build();

StartFileTransferResponse response =
    helper.startFileTransfer(request, metadata, options);
```

The `startFileTransfer` method accepts an optional third parameter:

| Method | SDK Request Type | Returns |
|--------|-----------------|---------|
| `startFileTransfer(request, metadata, options)` | `StartFileTransferRequest` | `StartFileTransferResponse` |

**`EventEmissionMode`** controls which events are published:

| Mode | Behavior |
|------|----------|
| `INDIVIDUAL_FILE_EVENTS_ONLY` | Default. One enriched event per file (backward-compatible). |
| `WHOLE_TRANSFER_COMPLETION_ONLY` | Single batch completion event when all files complete. No per-file events. |
| `INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION` | Both per-file events and a batch completion event. |

Passing `null` for `options` (or using the 2-arg overload) is equivalent to `INDIVIDUAL_FILE_EVENTS_ONLY`.

**`batchTimeout(Duration)`** controls when a timeout event fires if not all files resolve:

| Value | Behavior |
|-------|----------|
| Not set (default) | 1 hour when any batch emission mode is active |
| `Duration.ofMinutes(30)` | Custom timeout (must be ≥ 2 seconds) |
| `Duration.ZERO` | Disabled — no timeout event will be published |

The timeout is ignored when `emissionMode` is `INDIVIDUAL_FILE_EVENTS_ONLY` (no batch tracking).

> **Constraint**: `batchTimeout` must be less than `ttlDuration + 1 hour` (the effective record TTL). Otherwise the DynamoDB record may expire before the timeout fires, and an `IllegalArgumentException` is thrown at call time.

### Exception Contract

All operations throw on failure instead of returning result variants:

| Condition | Exception |
|-----------|-----------|
| Metadata validation fails | `IllegalArgumentException` |
| Request is null | `IllegalArgumentException` |
| SDK call fails (network, throttle, auth) | `SdkException` propagates |
| SDK returns null/empty job ID | `IllegalStateException` |
| DynamoDB write fails (throttle, error) | `MetadataWriteException` |
| DynamoDB condition check fails | `MetadataWriteException` with "Unexpected duplicate metadata" message |
| `batchTimeout >= effective TTL` | `IllegalArgumentException` |

`MetadataWriteException` carries:
- `getJobId()` — the transfer/listing/move/delete ID
- `getSdkResponse()` — the raw SDK response (cast to the concrete type matching the method called)
- `getCause()` — the underlying DynamoDB exception

```java
try {
    StartFileTransferResponse response = helper.startFileTransfer(request, metadata);
    System.out.println("Transfer: " + response.transferId());
} catch (MetadataWriteException e) {
    // Transfer is in progress — proceed without enriched events
    log.warn("Transfer {} started but metadata lost", e.getJobId(), e);
}
```

### Metadata Format

Metadata must be a valid JSON **object** (not array, not primitive).

Constraints:
- Maximum size: **8,000 bytes** (8 KB). Exceeding this throws `IllegalArgumentException`.
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

> **Note**: When using an imported table via CDK construct props, ensure the Joiner Lambda role has the required DynamoDB permissions. See [Architecture — CDK Construct Reference](ARCHITECTURE.md#cdk-construct-reference) for details.

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

### Batch Completion Event Format

When using `WHOLE_TRANSFER_COMPLETION_ONLY` or `INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION`, a single batch event is published once all files in the transfer complete:

```json
{
  "source": "custom.sftp-connector-helper",
  "detail-type": "SFTP Connector Whole File Send Transfer Completed - CUSTOM",
  "detail": {
    "transfer-id": "xfer-abc123",
    "connector-id": "c-1234567890abcdef0",
    "status-code": "ALL_COMPLETED",
    "file-count": 3,
    "completed-count": 3,
    "failed-count": 0,
    "_helper_metadata": {
      "orderId": "ORD-001",
      "customer": "ACME"
    },
    "files": [
      {
        "connector-id": "c-1234567890abcdef0",
        "transfer-id": "xfer-abc123",
        "status-code": "COMPLETED",
        "file-path": "/outbound/invoice-001.csv",
        "_helper_metadata": { "orderId": "ORD-001", "customer": "ACME" }
      }
    ]
  }
}
```

**Detail-types** for batch events:
- `SFTP Connector Whole File Send Transfer Completed - CUSTOM`
- `SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM`
- `SFTP Connector Whole File Send Transfer Timed Out - CUSTOM`
- `SFTP Connector Whole File Retrieve Transfer Timed Out - CUSTOM`

**`status-code`** values (batch events):
| Value | Meaning |
|-------|---------|
| `ALL_COMPLETED` | Every file has `status-code: "COMPLETED"` |
| `ALL_FAILED` | Every file has status `FAILED` |
| `PARTIAL_FAILURE` | Mix of completed and failed files |
| `TIMED_OUT` | Batch timeout fired before all files resolved |

### Batch Timeout Event Format

When the configured batch timeout expires before all files complete, a timeout event is published:

```json
{
  "source": "custom.sftp-connector-helper",
  "detail-type": "SFTP Connector Whole File Send Transfer Timed Out - CUSTOM",
  "detail": {
    "transfer-id": "xfer-abc123",
    "connector-id": "c-1234567890abcdef0",
    "status-code": "TIMED_OUT",
    "file-count": 5,
    "completed-count": 3,
    "failed-count": 0,
    "timed-out-count": 2,
    "_helper_metadata": {
      "orderId": "ORD-001",
      "customer": "ACME"
    },
    "files": [
      {
        "connector-id": "c-1234567890abcdef0",
        "transfer-id": "xfer-abc123",
        "status-code": "COMPLETED",
        "file-path": "/outbound/invoice-001.csv",
        "_helper_metadata": { "orderId": "ORD-001", "customer": "ACME" }
      },
      {
        "file-transfer-id": "unknown",
        "status-code": "TIMED_OUT",
        "file-path": "/outbound/invoice-004.csv",
        "_helper_metadata": { "orderId": "ORD-001", "customer": "ACME" }
      }
    ]
  }
}
```

Files that resolved before the timeout have their full Transfer Family event detail. Files that did not resolve have `status-code: "TIMED_OUT"` and `file-transfer-id: "unknown"`.

> **Note**: A timeout event and a normal completion event are mutually exclusive for the same transfer. If all files complete before the timeout, only the completion event is published. If the timeout fires first, only the timeout event is published (even if remaining files complete later).

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

Key metrics to watch — see [Architecture — CloudWatch Metrics](ARCHITECTURE.md#cloudwatch-metrics) for full metric definitions and dimensions.

| Metric | Alert Threshold | Action |
|--------|----------------|--------|
| `OrphanedRecords` | > 0 | Investigate: app not writing metadata before Transfer Family events arrive |
| `InvalidMetadata` | > 0 | Fix caller: metadata must be a JSON object |
| `UnknownOperationType` | > 0 | New Transfer Family event type? Update `field_mapping.py` |

AWS-managed metrics:
| Metric | Namespace | Alert Threshold |
|--------|-----------|----------------|
| Pipe IteratorAge | `AWS/Pipes` | > 60s for 3 min → Joiner stalled |
| Event Writer Errors | `AWS/Lambda` | > 0 |
| Joiner Errors | `AWS/Lambda` | > 0 |

### DLQ Inspection

Two DLQs capture failed processing — see [Architecture — Dead Letter Queues](ARCHITECTURE.md#dead-letter-queues) for retry configuration and trigger conditions.

Inspect with:
```bash
aws sqs receive-message --queue-url <dlq-url> --max-number-of-messages 10 --profile <profile>
```

### Orphan Alert Response

When you receive an SNS orphan alert — see [Architecture — Orphan Detection](ARCHITECTURE.md#orphan-detection) for the internal detection mechanism:

| Symptom | Action |
|---------|--------|
| App failing to write metadata entirely | Fix error handling — this is NOT a timing race, the system handles event-before-metadata ordering gracefully |
| Connector ID doesn't match a tracked connector | Using SFTP Connector without the helper — expected |
| Legitimate one-off orphan | No action needed (TTL will clean up) |
| Systematic orphans | App may be crashing before calling the helper — check app logs |

### Log Groups

| Log Group | Content |
|-----------|---------|
| `/aws/lambda/sftp-connector-helper-event-writer` | Event capture, field mapping |
| `/aws/lambda/sftp-connector-helper-joiner` | Correlation, publishing, orphan detection |

All logs are structured JSON with fields: `job_id`, `connector_id`, `operation`, `message`.

## Security Considerations

### Metadata Content

Metadata you pass to the helper flows through multiple AWS services in plaintext:

- **Stored** in DynamoDB (encrypted at rest with AWS-managed keys by default)
- **Logged** in Lambda structured logs (CloudWatch Logs)
- **Published** verbatim as `_helper_metadata` in EventBridge events
- **Visible** to any consumer subscribed to the dedicated bus

**Do not put in metadata**:
- Secrets, API keys, or credentials
- Unencrypted PII (names, emails, addresses) — use opaque identifiers or tokens instead
- Data subject to regulatory constraints (PHI, PCI) unless your entire pipeline is compliant

**Recommended approach**: Store sensitive data in your own systems and pass only correlation identifiers (order IDs, customer reference codes) in metadata.

### Event Bus Access Control

Any IAM principal with `events:PutRule` + `events:PutTargets` on the dedicated bus (`sftp-connector-helper-bus`) can subscribe and read all enriched events including metadata. Restrict bus access to trusted roles:

```json
{
  "Effect": "Allow",
  "Action": ["events:PutRule", "events:PutTargets"],
  "Resource": "arn:aws:events:<region>:<account>:rule/sftp-connector-helper-bus/*",
  "Condition": {
    "StringEquals": {"aws:PrincipalOrgID": "o-your-org-id"}
  }
}
```

### Event Bus Logging

By default, full event detail — including your metadata — is written to CloudWatch Logs via the dedicated bus. In sensitive environments, disable bus-level logging via the CDK construct configuration (see [Architecture — CDK Construct Reference](ARCHITECTURE.md#cdk-construct-reference)), or ensure log group access is restricted.

### Consumer Least-Privilege

Consumer Lambdas should only have:
- `events:DescribeRule` on their own rule
- Invoke permission granted via the EventBridge target (CDK handles this automatically with `targets.LambdaFunction`)
- No direct `dynamodb:*` access to the helper table (consumers read from EventBridge, not DynamoDB)

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

## Best Practices

### Retry at the Business Level, Not the Helper Level

The SDK call is not idempotent — retrying the helper starts a **new** transfer. Wrap retries around your entire business operation with a stable idempotency key:

```java
// ✗ WRONG — retrying the helper creates duplicate transfers
for (int i = 0; i < 3; i++) {
    try {
        helper.startFileTransfer(request, metadata);
        break;
    } catch (MetadataWriteException e) { /* don't retry */ }
}

// ✓ CORRECT — retry the business operation with idempotency at your layer
public void processOrder(Order order) {
    if (alreadySubmitted(order.id())) return; // your idempotency check

    try {
        var response = helper.startFileTransfer(request, metadata);
        markSubmitted(order.id(), response.transferId());
    } catch (MetadataWriteException e) {
        // Transfer is running but won't produce enriched event.
        // Log for manual reconciliation; don't retry.
        var transferResponse = (StartFileTransferResponse) e.getSdkResponse();
        log.warn("Transfer {} has no metadata correlation", transferResponse.transferId());
        markSubmitted(order.id(), transferResponse.transferId());
    }
}
```

### Graceful Degradation

When DynamoDB is unavailable, `MetadataWriteException` is thrown. Decide whether to proceed without correlation or abort:

```java
try {
    var response = helper.startFileTransfer(request, metadata);
    System.out.println("Transfer: " + response.transferId());
} catch (MetadataWriteException e) {
    // Option A: Accept — transfer runs, no enriched event
    log.warn("Proceeding without metadata for transfer {}", e.getJobId());
    trackManually(e.getJobId(), metadata);

    // Option B: Abort — if enriched events are critical to your workflow
    // cancelOrCompensate(e.getJobId());
}
```

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
