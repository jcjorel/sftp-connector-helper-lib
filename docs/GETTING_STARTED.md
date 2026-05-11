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
    <version>2.0.0</version>
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

    StartFileTransferResponse response =
        helper.startFileTransfer(request, metadata);

    System.out.println("Transfer started: " + response.transferId());
    // MetadataWriteException propagates if metadata write fails
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

See [Architecture — Data Flow](ARCHITECTURE.md#happy-path-single-file-operation) for the full pipeline diagram.

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

var response = helper.startFileTransfer(request, metadata);

System.out.println("Batch transfer started: " + response.transferId());
// You will receive 5 separate enriched events, one per file
```

**Expected output**:
```
Batch transfer started: t-xyz7890abcdef123
```

**What just happened**:
1. Transfer Family starts 5 parallel file transfers under one `transferId`.
2. The helper writes your metadata once for the entire batch.
3. As each file completes, the framework joins your metadata with each per-file event and publishes 5 enriched events — one per file.

See [Architecture — Fan-Out Path](ARCHITECTURE.md#fan-out-path-multi-file-transfer) for how the internal fan-out mechanism works.

Your consumer receives 5 events, each with:
- `transfer-id` — the shared batch ID
- `file-transfer-id` — unique per file
- `file-path` — which file completed
- `_helper_metadata` — your batch metadata on every event

---

## Scenario 4: Retrieve a File from the SFTP Server

**Goal**: Download a file from the remote SFTP server to S3 and attach business metadata.

```java
import io.github.jcjorel.sftpconnectorhelper.*;
import software.amazon.awssdk.services.transfer.model.*;

StartFileTransferRequest request = StartFileTransferRequest.builder()
    .connectorId("c-1234567890abcdef0")
    .retrieveFilePaths("/remote/invoices/invoice-101.csv")
    .localDirectoryPath("/my-bucket/inbound")
    .build();

String metadata = """
    {"action":"retrieve","source":"partner-sftp","expectedFile":"invoice-101.csv"}
    """;

var response = helper.startFileTransfer(request, metadata);

System.out.println("Retrieve started: " + response.transferId());
```

**Expected output**:
```
Retrieve started: t-ret7890abcdef123
```

**What just happened**:
1. Transfer Family started downloading the file from the remote SFTP server to your S3 bucket at `/my-bucket/inbound/invoice-101.csv`.
2. The helper wrote your metadata to DynamoDB (same mechanism as file send).
3. When the download completes, you receive an enriched event with `detail-type: "SFTP Connector File Retrieve Completed"` and your metadata in `_helper_metadata`.

The `retrieveFilePaths` format is the remote path on the SFTP server. The `localDirectoryPath` is `/<bucket>/<prefix>` where the file will be stored in S3. Multi-file retrieve works identically to multi-file send (fan-out with per-file events).

---

## Scenario 5: List a Remote Directory

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

var response = helper.startDirectoryListing(request, metadata);

System.out.println("Listing started: " + response.listingId());
```

When the enriched event arrives, use `DirectoryListingFilter` to extract matching files:

```java
import io.github.jcjorel.sftpconnectorhelper.DirectoryListingFilter;
import io.github.jcjorel.sftpconnectorhelper.DirectoryListingResult;
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

## Scenario 6: Move a Remote File

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

var response = helper.startRemoteMove(request, metadata);

System.out.println("Move started: " + response.moveId());
```

**Expected output**:
```
Move started: m-789abc012def345
```

**What just happened**: The file is being moved on the remote SFTP server. When complete, you'll receive an enriched event with `detail-type: "SFTP Connector Remote Move Completed"` and your metadata in `_helper_metadata`.

> **Note**: The file must exist on the remote server. If you need to move a file you just sent, wait for the send's enriched event (status `COMPLETED`) before issuing the move.

---

## Scenario 7: Delete a Remote File

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

var response = helper.startRemoteDelete(request, metadata);

System.out.println("Delete started: " + response.deleteId());
```

**Expected output**:
```
Delete started: d-456abc789def012
```

**What just happened**: Same pattern as move — the enriched event arrives with `detail-type: "SFTP Connector Remote Delete Completed"` and your metadata.

---

## Scenario 8: Handle Exceptions Correctly

**Goal**: Understand and handle the exception contract in production code.

```java
try {
    StartFileTransferResponse response = helper.startFileTransfer(request, metadata);
    // Happy path: transfer running + metadata correlated
    log.info("Transfer {} will produce enriched event", response.transferId());
} catch (MetadataWriteException e) {
    // Transfer IS running, but no enriched event will be produced.
    // The SDK response is available for manual tracking.
    var transferResponse = (StartFileTransferResponse) e.getSdkResponse();
    log.warn("Transfer {} running but metadata write failed: {}",
        transferResponse.transferId(), e.getMessage());
    // Consider: retry at application level, or track manually
}
```

**Key insight**: The SDK call is **not** idempotent — each call starts a new transfer. See [User Guide — Best Practices](USER_GUIDE.md#best-practices) for retry patterns and the full exception contract.

---

## Scenario 9: Subscribe to Orphan Alerts

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

## Scenario 10: Batch Completion with FileTransferOptions

**Goal**: Send multiple files and receive a single "all done" event instead of tracking per-file completion yourself.

```java
import io.github.jcjorel.sftpconnectorhelper.*;
import software.amazon.awssdk.services.transfer.model.*;

List<String> files = List.of(
    "/my-bucket/batch/report-1.csv",
    "/my-bucket/batch/report-2.csv",
    "/my-bucket/batch/report-3.csv"
);

StartFileTransferRequest request = StartFileTransferRequest.builder()
    .connectorId("c-1234567890abcdef0")
    .sendFilePaths(files)
    .remoteDirectoryPath("/uploads/daily")
    .build();

String metadata = """
    {"batchId":"BATCH-2026-05-10","workflow":"daily-export"}
    """;

FileTransferOptions options = FileTransferOptions.builder()
    .emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY)
    .batchTimeout(Duration.ofMinutes(30))  // timeout if not all files complete within 30 min
    .build();

var response = helper.startFileTransfer(request, metadata, options);

System.out.println("Batch transfer started: " + response.transferId());
// You will receive ONE batch completion event when all 3 files are done
```

**Expected output**:
```
Batch transfer started: t-batch456def789
```

Your consumer receives a single batch completion event:

```json
{
  "source": "custom.sftp-connector-helper",
  "detail-type": "SFTP Connector Whole File Send Transfer Completed - CUSTOM",
  "detail": {
    "transfer-id": "t-batch456def789",
    "connector-id": "c-1234567890abcdef0",
    "status-code": "ALL_COMPLETED",
    "file-count": 3,
    "completed-count": 3,
    "failed-count": 0,
    "_helper_metadata": {"batchId": "BATCH-2026-05-10", "workflow": "daily-export"},
    "files": [...]
  }
}
```

**What just happened**:
1. The helper recorded your metadata along with the batch-tracking configuration (emission mode, expected file count, transfer direction, and timeout deadline).
2. As each file completed, the framework tracked its status — but did **not** publish individual enriched events (suppressed by `WHOLE_TRANSFER_COMPLETION_ONLY`).
3. When the last file completed, the framework published the batch completion event with all file statuses aggregated.
4. If not all files complete within 30 minutes, the framework publishes a **timeout event** instead (see below).

**Batch timeout** defaults to 1 hour when any batch emission mode is active. Set `Duration.ZERO` to disable it. See [User Guide — Batch Completion Events](USER_GUIDE.md#batch-completion-events-filetransferoptions) for the full API reference.

### Handling Timeout Events

If some files don't complete before the timeout, your consumer receives a timeout event instead of (or in addition to) the completion event:

```python
def lambda_handler(event, context):
    detail = event["detail"]
    detail_type = event["detail-type"]
    status = detail["status-code"]

    if status == "TIMED_OUT":
        # Not all files completed within the configured timeout
        timed_out = detail["timed-out-count"]
        completed = detail["completed-count"]
        total = detail["file-count"]
        print(f"⏱ Batch timed out: {completed}/{total} completed, {timed_out} timed out")

        # Inspect per-file statuses
        for f in detail["files"]:
            if f["status-code"] == "TIMED_OUT":
                print(f"  ✗ {f['file-path']} — did not complete")
    else:
        # Normal completion: ALL_COMPLETED, ALL_FAILED, or PARTIAL_FAILURE
        print(f"✓ Batch {status}: {detail['completed-count']}/{detail['file-count']} completed")
```

The timeout event has `detail-type` ending in `"Timed Out - CUSTOM"` (e.g., `"SFTP Connector Whole File Send Transfer Timed Out - CUSTOM"`). Files that didn't resolve have per-file `status-code: "TIMED_OUT"`.

See [Architecture — Whole Transfer Completion](ARCHITECTURE.md#whole-transfer-completion-batch-tracking) for how the internal batch tracking mechanism works.

**Other modes**: Use `INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION` to receive both per-file events *and* the batch event. See [User Guide — Batch Completion Events](USER_GUIDE.md#batch-completion-events-filetransferoptions) for the full API reference.

---

## Scenario 11: Reactive SQS Long-Polling Consumer (Java)

**Goal**: Efficiently consume enriched events from an SQS queue attached to the dedicated EventBridge bus using the AWS SDK v2 async client — no threads blocked while waiting for messages.

### Step 1 — Attach an SQS queue to the EventBridge bus

Create a rule that routes enriched events to an SQS queue (CDK example):

```python
from aws_cdk import aws_sqs as sqs, aws_events as events, aws_events_targets as targets

queue = sqs.Queue(self, "EnrichedEventsQueue",
    visibility_timeout=Duration.seconds(30),
)

events.Rule(self, "AllEnrichedEventsToSqs",
    event_bus=helper_construct.event_bus,
    event_pattern=events.EventPattern(
        source=["custom.sftp-connector-helper"],
    ),
    targets=[targets.SqsQueue(queue)],
)
```

### Step 2 — Reactive long-polling consumer

Add the SQS async dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sqs</artifactId>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.18.2</version>
</dependency>
```

The consumer uses `receiveMessage` with `waitTimeSeconds(20)` for long polling and chains async calls reactively — no thread is blocked while waiting:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class EnrichedEventConsumer implements AutoCloseable {

    private final SqsAsyncClient sqs = SqsAsyncClient.create();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String queueUrl;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public EnrichedEventConsumer(String queueUrl) {
        this.queueUrl = queueUrl;
    }

    public void start() {
        pollLoop();
    }

    private void pollLoop() {
        if (!running.get()) return;

        sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .waitTimeSeconds(20)          // long poll — no cost while idle
                .maxNumberOfMessages(10)
                .build())
            .thenAccept(response -> {
                for (Message msg : response.messages()) {
                    processMessage(msg);
                }
            })
            .whenComplete((ignored, error) -> {
                if (error != null) {
                    System.err.println("Poll error: " + error.getMessage());
                }
                pollLoop(); // schedule next poll — fully non-blocking
            });
    }

    private void processMessage(Message msg) {
        try {
            JsonNode event = mapper.readTree(msg.body());
            String detailType = event.path("detail-type").asText();
            JsonNode detail = event.path("detail");
            String status = detail.path("status-code").asText();
            JsonNode metadata = detail.path("_helper_metadata");

            System.out.printf("[%s] status=%s orderId=%s customer=%s%n",
                detailType, status,
                metadata.path("orderId").asText("N/A"),
                metadata.path("customer").asText("N/A"));

            // Delete after successful processing
            sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(msg.receiptHandle())
                .build());

        } catch (Exception e) {
            System.err.println("Failed to process message: " + e.getMessage());
            // Message returns to queue after visibility timeout
        }
    }

    @Override
    public void close() {
        running.set(false);
        sqs.close();
    }

    public static void main(String[] args) {
        var consumer = new EnrichedEventConsumer(
            "https://sqs.<region>.amazonaws.com/<account>/EnrichedEventsQueue");
        consumer.start();

        // Keep running until interrupted
        Runtime.getRuntime().addShutdownHook(new Thread(consumer::close));
    }
}
```

**Expected output** (when a transfer completes):
```
[SFTP Connector File Send Completed] status=COMPLETED orderId=ORD-42 customer=ACME
```

**What just happened**:
1. `waitTimeSeconds(20)` enables SQS long polling — the call returns immediately when messages arrive, or after 20 seconds if idle. No CPU or threads are consumed while waiting.
2. `thenAccept` + `whenComplete` chain the next poll reactively. The single async I/O thread handles everything — no thread pool needed for polling.
3. Each message body is the full EventBridge event JSON. Your business metadata is at `detail._helper_metadata`.
4. On success, the message is deleted. On failure, it becomes visible again after the queue's visibility timeout for automatic retry.

> **Tip**: For production, add a DLQ to the SQS queue (e.g., `maxReceiveCount: 3`) so poison messages don't loop forever. See [User Guide — Operational Runbook](USER_GUIDE.md#operational-runbook) for monitoring guidance.

---

## Next Steps

- **[User Guide](USER_GUIDE.md)** — Full API reference, event format details, operational runbook, troubleshooting
- **[Architecture](ARCHITECTURE.md)** — System internals, data flow diagrams, CDK construct reference, idempotency contract
