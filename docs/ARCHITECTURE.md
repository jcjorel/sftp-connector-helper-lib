# Architecture

## System Overview

The SFTP Connector Helper framework correlates business metadata with AWS Transfer Family SFTP Connector events through a serverless pipeline built on DynamoDB, EventBridge, and Lambda.

```
┌─────────────────┐       ┌──────────────┐       ┌──────────────────┐
│  Your App       │       │  Transfer    │       │  Default         │
│  (Java Helper)  │       │  Family      │       │  EventBridge Bus │
└────┬────────────┘       └──────┬───────┘       └────────┬─────────┘
     │                           │                         │
     │ 1. SDK call               │ 2. Async event          │
     │ 2. Write metadata         │    (detail-type:        │
     │    to DynamoDB            │     "SFTP Connector…")  │
     ▼                           ▼                         │
┌──────────────────────────────────────────┐               │
│            DynamoDB Table                 │◄──────────────┘
│  PK: jobId                               │   3. Event Writer Lambda
│  metadata | eventResult | transferId     │      (EventBridge → Lambda)
│  GSI: transferId-index                   │
└────────────────┬─────────────────────────┘
                 │ DynamoDB Streams (NEW_AND_OLD_IMAGES)
                 ▼
┌──────────────────────────────────┐
│  EventBridge Pipe (batch_size=1) │
└────────────────┬─────────────────┘
                 ▼
┌──────────────────────────────────┐       ┌─────────────────────────┐
│  Joiner Lambda                   │──────▶│  Dedicated EventBridge  │
│  - Completeness check            │       │  Bus (enriched events)  │
│  - Fan-out metadata copy         │       └─────────────────────────┘
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
| `joiner_memory` | `int` | 256 | Joiner Lambda memory (MB) |
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

**Enriched event source rewrite**: When the Joiner publishes to the dedicated bus, it rewrites the `source` field from `"aws.transfer"` to `"custom.sftp-connector-helper"`. The `detail-type` is preserved unchanged. Consumer rules must match on `source: ["custom.sftp-connector-helper"]`.

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

## Limitations

- **Single-region**: No cross-region replication; deploy per region
- **Singleton**: One deployment per account/region (fixed table and bus names)
- **DynamoDB RETAIN**: Table uses `RemovalPolicy.RETAIN` — `cdk destroy` won't delete it
- **No VPC endpoint configuration exposed**: Lambdas run in default VPC-less mode
- **Event bus logging**: Only configurable when creating a new bus (not on imported buses)
