# Architecture

## System Overview

The SFTP Connector Helper framework correlates business metadata with AWS Transfer Family SFTP Connector events through a serverless pipeline built on DynamoDB, EventBridge, and Lambda.

```
┌─────────────────┐       ┌──────────────┐        ┌──────────────────┐
│  Your App       │       │  Transfer    │        │  Default         │
│  (Java Helper)  │       │  Family      │        │  EventBridge Bus │
└────┬────────────┘       └──────┬───────┘        └────────┬─────────┘
     │                           │                         │
     │ 1. SDK call               │ 2. Async event          │
     │ 2. Write metadata         │    (detail-type:        │
     │    to DynamoDB            │     "SFTP Connector…")  │
     ▼                           ▼                         │
┌──────────────────────────────────────────┐               │
│            DynamoDB Table                │◄──────────────┘
│  PK: jobId                               │   3. Event Writer Lambda
│  metadata | eventResult | transferId     │      (EventBridge → Lambda)
│  GSI: transferId-index                   │
└────────────────┬─────────────────────────┘
                 │ DynamoDB Streams (NEW_AND_OLD_IMAGES)
                 ▼
┌──────────────────────────────────┐
│  EventBridge Pipe (batch_size=4) │
└────────────────┬─────────────────┘
                 ▼
┌──────────────────────────────────┐        ┌─────────────────────────┐
│  Joiner Lambda                   │──────▶│  Dedicated EventBridge  │
│  - Completeness check            │        │  Bus (enriched events)  │
│  - Fan-out metadata copy         │        └─────────────────────────┘
│  - Orphan detection (TTL expiry) │
│  - Publish enriched event        │──────▶ SNS (orphan alerts)
└──────────────────────────────────┘
```

## Data Flow

### Happy Path (Single-File Operation)

1. Application calls `helper.startFileTransfer(request, metadata)`
2. Java helper executes the SDK call, receives `transferId`
3. Java helper writes `{jobId, metadata, ttl}` to DynamoDB with `attribute_not_exists(metadata)` condition
4. Transfer Family emits event to default EventBridge bus
5. Event Writer Lambda captures event, writes `{jobId, eventResult, ttl}` to same DynamoDB item
6. DynamoDB Stream triggers Joiner Lambda via EventBridge Pipe
7. Joiner detects both `metadata` and `eventResult` present → publishes enriched event

### Fan-Out Path (Multi-File Transfer)

`StartFileTransfer` with N files produces N per-file events (each containing both `transfer-id` and `file-transfer-id`). There is no batch-level completion event. The framework handles this via:

1. Java helper writes a **master record** with `jobId = transferId` and staggered TTL (base TTL + 1 hour, implemented in the Java helper's `writeMetadataAndReturn` method)
2. Event Writer writes **per-file records** with `jobId = file-transfer-id` and `transferId` attribute
3. Joiner detects per-file records (has `eventResult` + `transferId`, no `metadata`) → reads master record via `GetItem`, copies metadata from master to per-file record
4. Joiner detects master record (has `metadata`, no `eventResult`, no `transferId`) → fans out metadata to existing per-file records via GSI query

The master record never receives an `eventResult` (Transfer Family does not emit a batch-level event). It expires via TTL and is handled by orphan detection branch 2 (GSI query confirms per-file records exist → no alert).

For a multi-file transfer with N files, expect approximately 2N+1 DynamoDB write operations (1 master + N event-writer per-file + N metadata copies).

### Orphan Detection

When a DynamoDB item expires (TTL REMOVE event), the Joiner evaluates four branches:

1. **Both `metadata` AND `eventResult` present** → normal expiry, no action
2. **Master record** (has `metadata`, no `eventResult`, no `transferId`) → queries GSI for per-file records; if none exist, triggers orphan alert (metadata was written but Transfer Family never produced events). `ConnectorId` dimension is `UNKNOWN` (no event to extract from).
3. **Metadata-only** (has `metadata`, no `eventResult`, not a master record) → orphan alert (per-file record that was never joined). `ConnectorId` dimension is `UNKNOWN`.
4. **Event-only** (has `eventResult`, no `metadata`) → orphan alert via SNS + `OrphanedRecords` CloudWatch metric. `ConnectorId` extracted from stored event.

## CDK Construct Reference

### `SftpConnectorHelperProps`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `existing_table_arn` | `str \| None` | `None` | Import existing DynamoDB table (must provide both table + stream ARN) |
| `existing_table_stream_arn` | `str \| None` | `None` | Stream ARN of existing table |
| `existing_bus_arn` | `str \| None` | `None` | Import existing EventBridge bus |
| `ttl_duration` | `Duration` | 1 day | TTL for DynamoDB records |
| `event_writer_memory` | `int` | 256 | Event Writer Lambda memory (MB) |
| `event_writer_timeout` | `Duration` | 30s | Event Writer Lambda timeout |
| `joiner_memory` | `int` | 512 | Joiner Lambda memory (MB) |
| `joiner_timeout` | `Duration` | 30s | Joiner Lambda timeout |
| `event_bus_log_level` | `str \| None` | `"INFO"` | Bus log level; ignored when `existing_bus_arn` is set. When enabled, full event detail is included in logs (cost and data-sensitivity implications). |

### Exposed Properties

| Property | Type | Description |
|----------|------|-------------|
| `table` | `ITable` | DynamoDB table (for granting permissions) |
| `event_bus` | `IEventBus` | Dedicated bus (for adding consumer rules) |
| `orphan_topic` | `ITopic` | SNS topic for orphan/alarm notifications |

### Usage

```python
from sftp_connector_helper import SftpConnectorHelper, SftpConnectorHelperProps

helper = SftpConnectorHelper(self, "Helper", SftpConnectorHelperProps(
    ttl_duration=Duration.hours(12),
    joiner_memory=512,
))

# Grant your consumer Lambda permission to receive events
helper.event_bus.grant_put_events_to(my_consumer_lambda)
```

See [Getting Started](GETTING_STARTED.md) for a hands-on deployment walkthrough.

## Idempotency Contract

| Component | Write Type | Condition | Behavior on Conflict |
|-----------|-----------|-----------|---------------------|
| Java Helper (metadata) | `UpdateItem` | `attribute_not_exists(metadata)` | Returns `MetadataAlreadyExists` — SDK call already succeeded |
| Event Writer (eventResult) | `UpdateItem` | Unconditional | Last-write-wins (events are immutable per jobId) |
| Joiner (metadata copy) | `UpdateItem` | Unconditional DynamoDB write (no condition expression), but application-level loop prevention skips records where OldImage already has metadata | Last-write-wins (idempotent — source metadata is immutable per master record) |
| Joiner (publish) | Completeness check | `OldImage` didn't have both fields | Skips already-published records (loop prevention) |

**Important**: The SDK call itself (e.g., `startFileTransfer`) is NOT idempotent. Retrying the helper method will start a new transfer. Only the metadata correlation is safe to retry.

## DynamoDB Table Schema

| Attribute | Type | Role |
|-----------|------|------|
| `jobId` | String (PK) | Operation identifier (transferId, listingId, moveId, deleteId, or per-file composite) |
| `metadata` | String | JSON metadata from application |
| `eventResult` | String | Full EventBridge event JSON |
| `transferId` | String | Present on per-file records for GSI lookup |
| `ttl` | Number | Unix epoch expiry |

**GSI**: `transferId-index` (PK: `transferId`) — used by Joiner to copy metadata from master to per-file records.

## Supported Event Types

The Event Writer captures events matching `source: "aws.transfer"` with `detail-type` prefix `"SFTP Connector"` (configured via EventBridge rule prefix filter). Job ID extraction per operation:

| detail-type prefix | Job ID field | Per-file? |
|-------------------|-------------|-----------|
| `SFTP Connector File Send …` | `detail.transfer-id` | Yes, when both `file-transfer-id` and `transfer-id` present (uses `file-transfer-id` as jobId) |
| `SFTP Connector File Retrieve …` | `detail.transfer-id` | Yes, same logic as File Send |
| `SFTP Connector Directory Listing …` | `detail.listing-id` | No |
| `SFTP Connector Remote Move …` | `detail.move-id` | No |
| `SFTP Connector Remote Delete …` | `detail.delete-id` | No |

**Enriched event source rewrite**: When the Joiner publishes to the dedicated bus, it rewrites the `source` field from `"aws.transfer"` to `"custom.sftp-connector-helper"`. The `detail-type` is preserved unchanged. Consumer rules must match on `source: ["custom.sftp-connector-helper"]`. See [User Guide — Consuming Enriched Events](USER_GUIDE.md#consuming-enriched-events) for rule examples and event format.

**Per-file detection logic**: When a FILE_TRANSFER event contains both `file-transfer-id` and `transfer-id` in its detail, the Event Writer uses `file-transfer-id` as the DynamoDB jobId and stores `transfer-id` as a separate attribute for GSI-based fan-out metadata copy.

Unknown `detail-type` values (not matching any prefix above) are discarded with an `UnknownOperationType` CloudWatch metric emitted by the Event Writer Lambda.

## CloudWatch Metrics

All metrics are emitted to namespace `SftpConnectorHelper`:

| Metric | Dimensions | Meaning |
|--------|-----------|---------|
| `OrphanedRecords` | `ConnectorId`, (none) | Event arrived but no metadata was ever written |
| `InvalidMetadata` | `ConnectorId`, (none) | Metadata was not valid JSON object |
| `UnknownOperationType` | `ConnectorId`, (none) | Event Writer received unrecognized detail-type |

## Alarms

| Alarm | Threshold | Action |
|-------|-----------|--------|
| Pipe IteratorAge > 60s (3 consecutive 1-minute evaluation periods) | 60,000 ms | SNS → orphan topic |

## Dead Letter Queues

| Queue | Source | Trigger |
|-------|--------|---------|
| **EventWriterDLQ** | EventBridge → Lambda target | Event Writer Lambda invocation fails after 2 retries |
| **PipeDLQ** (`sftp-connector-helper-pipe-dlq`) | DynamoDB Streams → EventBridge Pipe | Joiner Lambda invocation fails after 3 retries or 1-hour record age (whichever comes first) |

Both queues use default SQS retention (4 days). Messages contain the original event payload for replay or inspection.

## Whole Transfer Completion (Batch Tracking)

The framework supports a batch-level completion signal for multi-file transfers via `FileTransferOptions`. This eliminates the need for consumers to track per-file completion counts.

### Emission Modes

| Mode | Individual per-file events | Batch completion event |
|------|---------------------------|----------------------|
| `INDIVIDUAL_FILE_EVENTS_ONLY` | ✓ Published | ✗ Not published |
| `WHOLE_TRANSFER_COMPLETION_ONLY` | ✗ Suppressed | ✓ Published |
| `INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION` | ✓ Published | ✓ Published |

### Additional DynamoDB Attributes (Master Record)

When `emissionMode` ≠ `INDIVIDUAL_FILE_EVENTS_ONLY`, the Java helper writes these additional attributes atomically with the metadata:

| Attribute | Type | Description |
|-----------|------|-------------|
| `emissionMode` | String | One of the three mode values above |
| `expectedFiles` | Number | Count of files in the transfer (from `sendFilePaths` or `retrieveFilePaths` size) |
| `transferDirection` | String | `"SEND"` or `"RETRIEVE"` — determines batch event detail-type |
| `connectorId` | String | Connector ID (extracted from master record context) |
| `fileStatuses` | Map | Per-file completion tracking map (keyed by SHA-256 hash of `file-transfer-id`) |
| `resolvedCount` | Number | Atomic counter of resolved files (incremented via `ADD`) |
| `batchEventPublished` | Boolean | Deduplication marker — prevents duplicate batch events |

### Batch Tracking Data Flow

1. **Java helper** writes master record with `emissionMode`, `expectedFiles`, `transferDirection`, and an initialized `fileStatuses` map.
2. **Joiner Lambda** processes each per-file stream event. When it detects the master has `expectedFiles` set:
   - Conditionally updates `fileStatuses` map with file status (uses `attribute_not_exists` to prevent double-counting)
   - Atomically increments `resolvedCount`
   - Checks `should_publish_individual_event(master)` — suppresses individual enriched events when mode is `WHOLE_TRANSFER_COMPLETION_ONLY`
3. **Batch guard** (`_check_and_publish_batch`): after each file resolution, checks if `resolvedCount == expectedFiles` and `batchEventPublished == false`. If both conditions met, assembles and publishes the batch completion event, then sets `batchEventPublished = true` as a best-effort dedup marker.

### Batch Completion Event Types

These are custom events produced by the Joiner (not Transfer Family events):

| detail-type | Trigger |
|-------------|---------|
| `SFTP Connector Whole File Send Transfer Completed - CUSTOM` | All files in a send transfer resolved |
| `SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM` | All files in a retrieve transfer resolved |

The event `source` is `"custom.sftp-connector-helper"` (same as individual enriched events).

### Batch Event Detail Structure

```json
{
  "transfer-id": "t-abc123",
  "connector-id": "c-1234567890abcdef0",
  "status-code": "ALL_COMPLETED",
  "file-count": 5,
  "completed-count": 5,
  "failed-count": 0,
  "_helper_metadata": { "batchId": "BATCH-001" },
  "files": [
    { "file-transfer-id": "ft-1", "status-code": "COMPLETED", "file-path": "/uploads/file1.csv", "_helper_metadata": {...} },
    ...
  ]
}
```

**`status-code` values** (batch events): `ALL_COMPLETED` | `ALL_FAILED` | `PARTIAL_FAILURE`

### Idempotency

- Per-file tracking uses `attribute_not_exists(#fs.#ftId)` condition — duplicate file events are safely ignored.
- On duplicate, the Joiner re-reads the master record and re-evaluates the batch guard (handles race conditions).
- `batchEventPublished` marker prevents duplicate batch events (best-effort — at-least-once delivery is possible in edge cases).

## Limitations

- **Single-region**: No cross-region replication; deploy per region
- **Singleton**: One deployment per account/region (fixed table and bus names)
- **DynamoDB RETAIN**: Table uses `RemovalPolicy.RETAIN` — `cdk destroy` won't delete it
- **No VPC endpoint configuration exposed**: Lambdas run in default VPC-less mode
- **Event bus logging**: Only configurable when creating a new bus (not on imported buses)
