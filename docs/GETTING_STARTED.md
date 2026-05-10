# Getting Started

A hands-on tutorial that takes you from first API call to production-ready patterns. Each scenario builds on the previous one.

## Prerequisites

Before starting, ensure you have:

- [ ] The stack deployed (see [README — Deploy](../README.md#deploy))
- [ ] Your connector ID (e.g., `c-1234567890abcdef0`)
- [ ] An S3 bucket accessible by the connector
- [ ] A remote directory path on the SFTP server
- [ ] IAM permissions: `transfer:Start*` on your connector + `dynamodb:UpdateItem` on the helper table (see [User Guide — IAM Permissions](USER_GUIDE.md#iam-permissions-required-by-caller))

Add the Maven dependency:

```xml
<dependency>
    <groupId>io.github.jcjorel</groupId>
    <artifactId>sftp-connector-helper</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Scenario 0: Verify Your Deployment

**Goal**: Confirm the helper infrastructure is working end-to-end using only the AWS CLI — no application code required.

### Step 1 — Check the stack resources exist

```bash
# Verify the DynamoDB table
aws dynamodb describe-table --table-name sftp-connector-helper \
    --query "Table.TableStatus" --output text \
    --profile $AWS_PROFILE --region $AWS_REGION
# Expected: ACTIVE

# Verify the dedicated EventBridge bus
aws events describe-event-bus --name sftp-connector-helper-bus \
    --query "Name" --output text \
    --profile $AWS_PROFILE --region $AWS_REGION
# Expected: sftp-connector-helper-bus
```

### Step 2 — Verify the event pipeline is wired

```bash
# Check the EventBridge Pipe exists and is running
aws pipes list-pipes --name-prefix sftp-connector-helper \
    --query "Pipes[0].CurrentState" --output text \
    --profile $AWS_PROFILE --region $AWS_REGION
# Expected: RUNNING

# Check the orphan SNS topic exists
aws sns list-topics --query "Topics[?contains(TopicArn,'sftp-connector-helper')]" \
    --output text --profile $AWS_PROFILE --region $AWS_REGION
# Expected: arn:aws:sns:<region>:<account>:sftp-connector-helper-...
```

**What this proves**: The DynamoDB table, EventBridge bus, event pipeline, and orphan alerting are all deployed and active. If any step fails, check your IAM permissions and that the stack deployed successfully (`make deploy`).

> **Note**: This does not trigger the full pipeline (no Transfer Family event is produced). To verify end-to-end event flow, proceed to Scenario 1 with a real connector.

---

## Scenario 1: Send a Single File

**Goal**: Send a file to the SFTP server and attach business metadata that will appear in the enriched event.

```java
import io.github.jcjorel.sftpconnectorhelper.*;
import software.amazon.awssdk.services.transfer.model.*;

try (SftpConnectorHelper helper = SftpConnectorHelper.builder().build()) {

    StartFileTransferRequest request = StartFileTransferRequest.builder()
        .connectorId("c-1234567890abcdef0")
        .sendFilePaths("/my-bucket/outbound/invoice-001.csv")
        .remoteDirectoryPath("/uploads")
        .build();

    String metadata = """
        {"orderId":"ORD-42","customer":"ACME","priority":"high"}
        """;

    SftpOperationResult<StartFileTransferResponse> result =
        helper.startFileTransfer(request, metadata);

    switch (result) {
        case SftpOperationResult.Success<StartFileTransferResponse> s ->
            System.out.println("Transfer started: " + s.response().transferId());
        case SftpOperationResult.MetadataWriteFailed<StartFileTransferResponse> f ->
            System.err.println("Transfer OK but metadata lost: " + f.cause().getMessage());
        case SftpOperationResult.MetadataAlreadyExists<StartFileTransferResponse> e ->
            System.err.println("Duplicate metadata for: " + e.jobId());
    }
}
```

**Expected output**:
```
Transfer started: t-abcdef1234567890
```

**What just happened**:
1. The helper called the Transfer Family `StartFileTransfer` API — your file is now being sent to the SFTP server.
2. It wrote your metadata JSON to DynamoDB with a conditional check (idempotent).
3. When Transfer Family completes the transfer, it emits an event. The framework's Joiner Lambda joins your metadata with that event and publishes an enriched event to the dedicated EventBridge bus.

The `sendFilePaths` format is `/<bucket>/<key>` — the file must already exist in S3.

---

## Scenario 2: Consume the Enriched Event

**Goal**: Write a Lambda that receives the enriched event with your business metadata.

Create an EventBridge rule targeting your consumer Lambda. See [User Guide — Consuming Enriched Events](USER_GUIDE.md#consuming-enriched-events) for CDK, Console, and CLI rule examples.

Your Lambda receives:

```python
def lambda_handler(event, context):
    detail = event["detail"]
    status = detail["status-code"]           # "COMPLETED" or "FAILED"
    file_path = detail["file-path"]          # "/uploads/invoice-001.csv"
    transfer_id = detail["transfer-id"]

    # Your metadata — exactly as you passed it
    meta = detail["_helper_metadata"]
    order_id = meta["orderId"]               # "ORD-42"
    customer = meta["customer"]              # "ACME"

    if status == "COMPLETED":
        print(f"✓ Order {order_id} for {customer}: file delivered")
    else:
        print(f"✗ Order {order_id}: transfer failed — {detail.get('failure-message')}")
```

**What just happened**: The enriched event carries the original Transfer Family event fields *plus* your `_helper_metadata` object. You can route, filter, and process events using your business context without any secondary lookups.

---

## Scenario 3: Send Multiple Files (Fan-Out)

**Goal**: Send 5 files in a single API call and receive one enriched event per file, each carrying the same metadata.

```java
List<String> files = List.of(
    "/my-bucket/batch/report-1.csv",
    "/my-bucket/batch/report-2.csv",
    "/my-bucket/batch/report-3.csv",
    "/my-bucket/batch/report-4.csv",
    "/my-bucket/batch/report-5.csv"
);

StartFileTransferRequest request = StartFileTransferRequest.builder()
    .connectorId("c-1234567890abcdef0")
    .sendFilePaths(files)
    .remoteDirectoryPath("/uploads/daily-batch")
    .build();

String metadata = """
    {"batchId":"BATCH-2026-05-10","fileCount":5,"source":"billing"}
    """;

var result = helper.startFileTransfer(request, metadata);

if (result instanceof SftpOperationResult.Success<StartFileTransferResponse> s) {
    System.out.println("Batch transfer started: " + s.response().transferId());
    // You will receive 5 separate enriched events, one per file
}
```

**Expected output**:
```
Batch transfer started: t-xyz7890abcdef123
```

**What just happened**:
1. Transfer Family starts 5 parallel file transfers under one `transferId`.
2. The helper writes a **master record** (keyed by `transferId`) with your metadata.
3. As each file completes, Transfer Family emits a per-file event with a unique `file-transfer-id`.
4. The Joiner copies metadata from the master record to each per-file record and publishes 5 enriched events.

Your consumer receives 5 events, each with:
- `transfer-id` — the shared batch ID
- `file-transfer-id` — unique per file
- `file-path` — which file completed
- `_helper_metadata` — your batch metadata on every event

---

## Scenario 4: List a Remote Directory

**Goal**: List files on the SFTP server and filter the results by pattern.

```java
import software.amazon.awssdk.services.transfer.model.*;

StartDirectoryListingRequest request = StartDirectoryListingRequest.builder()
    .connectorId("c-1234567890abcdef0")
    .remoteDirectoryPath("/incoming")
    .outputDirectoryPath("/my-bucket/listings")
    .build();

String metadata = """
    {"purpose":"poll-for-new-invoices","schedule":"hourly"}
    """;

var result = helper.startDirectoryListing(request, metadata);

if (result instanceof SftpOperationResult.Success<StartDirectoryListingResponse> s) {
    System.out.println("Listing started: " + s.response().listingId());
}
```

When the enriched event arrives, use `DirectoryListingFilter` to extract matching files:

```java
import software.amazon.awssdk.services.s3.S3Client;

DirectoryListingFilter filter = new DirectoryListingFilter(S3Client.create());

// enrichedEventJson is the full EventBridge event JSON from your consumer
DirectoryListingResult listing = filter.filter(enrichedEventJson, ".*\\.csv", null);

System.out.println("CSV files found: " + listing.files().size());
for (var file : listing.files()) {
    System.out.println("  " + file.get("filePath"));
}
System.out.println("Truncated: " + listing.truncated());
```

**Expected output**:
```
Listing started: l-abc123def456
CSV files found: 3
  /incoming/invoice-101.csv
  /incoming/invoice-102.csv
  /incoming/report-may.csv
Truncated: false
```

**What just happened**:
1. Transfer Family wrote the directory listing output to S3 (at the `outputDirectoryPath` you specified).
2. The enriched event contains `detail.output-file-location.bucket` and `key` pointing to that S3 object.
3. `DirectoryListingFilter` reads the S3 object and applies your regex — `".*\\.csv"` matches file paths containing `.csv`.

See [User Guide — Directory Listing Filter](USER_GUIDE.md#directory-listing-filter) for full parameter reference and error handling.

---

## Scenario 5: Move a Remote File

**Goal**: Rename or move a file on the SFTP server after processing.

```java
import software.amazon.awssdk.services.transfer.model.*;

StartRemoteMoveRequest request = StartRemoteMoveRequest.builder()
    .connectorId("c-1234567890abcdef0")
    .sourcePath("/incoming/invoice-101.csv")
    .targetPath("/archive/2026/05/invoice-101.csv")
    .build();

String metadata = """
    {"action":"archive","originalPath":"/incoming/invoice-101.csv","reason":"processed"}
    """;

var result = helper.startRemoteMove(request, metadata);

if (result instanceof SftpOperationResult.Success<StartRemoteMoveResponse> s) {
    System.out.println("Move started: " + s.response().moveId());
}
```

**Expected output**:
```
Move started: m-789abc012def345
```

**What just happened**: The file is being moved on the remote SFTP server. When complete, you'll receive an enriched event with `detail-type: "SFTP Connector Remote Move Completed"` and your metadata in `_helper_metadata`.

> **Note**: The file must exist on the remote server. If you need to move a file you just sent, wait for the send's enriched event (status `COMPLETED`) before issuing the move.

---

## Scenario 6: Delete a Remote File

**Goal**: Remove a file from the SFTP server.

```java
import software.amazon.awssdk.services.transfer.model.*;

StartRemoteDeleteRequest request = StartRemoteDeleteRequest.builder()
    .connectorId("c-1234567890abcdef0")
    .deletePath("/archive/2025/old-report.csv")
    .build();

String metadata = """
    {"action":"cleanup","retentionPolicy":"90-days-expired"}
    """;

var result = helper.startRemoteDelete(request, metadata);

if (result instanceof SftpOperationResult.Success<StartRemoteDeleteResponse> s) {
    System.out.println("Delete started: " + s.response().deleteId());
}
```

**What just happened**: Same pattern as move — the enriched event arrives with `detail-type: "SFTP Connector Remote Delete Completed"` and your metadata.

---

## Scenario 7: Handle Result Types Correctly

**Goal**: Understand and handle all three result types in production code.

```java
var result = helper.startFileTransfer(request, metadata);

switch (result) {
    case SftpOperationResult.Success<StartFileTransferResponse> s -> {
        // Happy path: transfer running + metadata correlated
        log.info("Transfer {} will produce enriched event", s.response().transferId());
    }

    case SftpOperationResult.MetadataWriteFailed<StartFileTransferResponse> f -> {
        // Transfer IS running, but no enriched event will be produced.
        // The transfer-id is still available for manual tracking.
        log.warn("Transfer {} running but metadata write failed: {}",
            f.response().transferId(), f.cause().getMessage());
        // Consider: retry at application level, or track manually
    }

    case SftpOperationResult.MetadataAlreadyExists<StartFileTransferResponse> e -> {
        // A previous call already wrote metadata for this job ID.
        // This means you called the helper twice — the SECOND SDK call started
        // a NEW transfer that won't have metadata correlation.
        log.error("Duplicate call detected for job {}. "
            + "The new transfer {} has no metadata.", e.jobId(), e.response().transferId());
        // Fix: don't retry the helper method; retry at a higher level
    }
}
```

**Key insight**: The SDK call is **not** idempotent — each call starts a new transfer. See [User Guide — Best Practices](USER_GUIDE.md#best-practices) for retry patterns and the full idempotency contract.

---

## Scenario 8: Subscribe to Orphan Alerts

**Goal**: Get notified when metadata correlation fails (events without metadata or metadata without events).

```python
from aws_cdk import aws_sns_subscriptions as subs

helper_construct.orphan_topic.add_subscription(
    subs.EmailSubscription("ops-team@example.com")
)
```

**What just happened**: You subscribed to the orphan topic exposed by the CDK construct. The framework automatically detects unmatched records at TTL expiry and publishes alerts to this topic.

See [User Guide — Orphan Alert Response](USER_GUIDE.md#orphan-alert-response) for diagnosis and recommended actions.

---

## Scenario 9: Track Multi-File Batch Completion

**Goal**: Detect when all files in a batch transfer have completed.

Transfer Family emits one event per file with no batch-level signal. Track completion in your consumer:

```python
import boto3

dynamodb = boto3.resource("dynamodb")
table = dynamodb.Table("my-batch-tracker")  # your own table

def lambda_handler(event, context):
    detail = event["detail"]
    meta = detail["_helper_metadata"]
    batch_id = meta["batchId"]
    expected = meta["fileCount"]

    # Atomic increment
    resp = table.update_item(
        Key={"batchId": batch_id},
        UpdateExpression="ADD completedCount :one SET #s = :status",
        ExpressionAttributeNames={"#s": "lastStatus"},
        ExpressionAttributeValues={":one": 1, ":status": detail["status-code"]},
        ReturnValues="UPDATED_NEW",
    )

    completed = int(resp["Attributes"]["completedCount"])
    if completed >= expected:
        print(f"Batch {batch_id}: all {expected} files done")
        # Trigger downstream workflow
```

**What just happened**: By including `fileCount` in your metadata (see Scenario 3), consumers know when the batch is complete without querying the source system.

See [User Guide — Best Practices](USER_GUIDE.md#best-practices) for retry strategies, graceful degradation, and idempotency patterns.

---

## Next Steps

- **[User Guide](USER_GUIDE.md)** — Full API reference, event format details, operational runbook, troubleshooting
- **[Architecture](ARCHITECTURE.md)** — System internals, data flow diagrams, CDK construct reference, idempotency contract
