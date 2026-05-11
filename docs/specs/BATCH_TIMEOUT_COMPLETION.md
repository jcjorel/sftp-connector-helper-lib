# Specification: Batch Timeout Completion Event

## Status: DRAFT

## Problem

The existing batch completion event (`SFTP Connector Whole File Send/Retrieve Transfer Completed - CUSTOM`) is only published when **all** expected files have been individually resolved (completed or failed). If one or more files never produce a Transfer Family event (e.g., the SFTP server silently drops the request, a network partition prevents the event from reaching EventBridge, or Transfer Family encounters an internal error), the batch completion event is **never emitted**. The consumer is left waiting indefinitely until the DynamoDB TTL expiry triggers orphan detection (25 hours by default).

## Solution

Introduce a **timeout-based batch completion event** that fires when a configurable duration (default: 1 hour) elapses without all files in a `startFileTransfer` operation being resolved. The timeout is driven by SQS message delay hopping, initiated server-side by the Joiner Lambda upon master record creation via DynamoDB Streams.

## Scope

**In scope:**
- Timeout detection for batch-tracked `startFileTransfer` operations (both send and retrieve)
- SQS-based delayed message hopping mechanism (15-minute hops to reach target timeout)
- Timeout event publication with resolved file details and placeholder entries for missing files
- Mutual exclusion between normal batch completion and timeout events
- Orphan alert suppression when timeout event was already published
- Configurable timeout duration via `FileTransferOptions` (default 1 hour, minimum 15 minutes)

**Out of scope:**
- Timeout for single-file transfers (covered by existing orphan detection)
- Timeout for `INDIVIDUAL_FILE_EVENTS_ONLY` mode (no batch tracking)
- Progress events (e.g., "7 of 10 files completed")

## Design Principles

1. **Always-on by default** — Timeout enabled automatically when `emissionMode ≠ INDIVIDUAL_FILE_EVENTS_ONLY`; disabled with `batchTimeout(Duration.ZERO)`
2. **No new client dependency in Java helper** — Timeout scheduling is a server-side concern; the helper only persists the target epoch
3. **Efficient DDB access** — SQS message carries all immutable data; Timeout Checker needs only a single `GetItem` by primary key at fire time
4. **Mutual exclusion** — Normal batch event and timeout event are mutually exclusive; whichever fires first suppresses the other
5. **At-least-once delivery** — Timeout events may duplicate but never be lost; consumers must be idempotent
6. **Shared Lambda** — Timeout checking runs in the Joiner Lambda (SQS event source mapping added alongside existing DynamoDB Streams source)
7. **Orphan detection as backstop** — If the DynamoDB Stream event for the master INSERT is lost or DLQ'd by the Pipe, the timeout is never scheduled. Orphan detection at TTL expiry (25h) remains the ultimate safety net. This is an accepted failure mode — the timeout mechanism is best-effort acceleration, not a replacement for orphan detection.

---

## Trigger Mechanism

### Architecture

```
Java Helper                Joiner Lambda              SQS Timeout Queue         Joiner Lambda (SQS path)
    │                          │                           │                          │
    │── write master ─────────▶│                           │                          │
    │   (DDB: jobId,           │                           │                          │
    │    batchTimeoutAt,       │── SendMessage ───────────▶│                          │
    │    filePaths, …)         │   DelaySeconds=900        │                          │
    │                          │   body={transferId,       │                          │
    │                          │    targetTimeoutAt,       │                          │
    │                          │    connectorId,           │                          │
    │                          │    expectedFiles,         │                          │
    │                          │    transferDirection,     │                          │
    │                          │    metadata, filePaths}   │                          │
    │                          │                           │──(after 900s)───────────▶│
    │                          │                           │                          │── now < targetTimeoutAt?
    │                          │                           │◀── re-enqueue(delay=900)─│   Yes → hop
    │                          │                           │                          │
    │                          │                           │──(after delay)──────────▶│── now >= targetTimeoutAt?
    │                          │                           │                          │   Yes → GetItem(PK)
    │                          │                           │                          │   → publish timeout event
```

### SQS Delay Hopping

SQS `DelaySeconds` maximum is 900 seconds (15 minutes). For a 1-hour timeout, the message hops 4 times:

| Hop | Time elapsed | Action |
|-----|-------------|--------|
| 1 | T+0 → T+15min | Message invisible; delivered to Lambda at T+15min |
| 2 | T+15min → T+30min | Lambda re-enqueues with DelaySeconds=900 |
| 3 | T+30min → T+45min | Lambda re-enqueues with DelaySeconds=900 |
| 4 | T+45min → T+60min | Lambda re-enqueues with DelaySeconds=remaining |
| Fire | T+60min | Lambda checks DDB and publishes timeout event |

The last hop uses `min(remaining, 900)` to handle non-aligned durations precisely.

---

## SQS Message Format

The message body carries all immutable master record data so the Joiner needs **no DynamoDB read during hops**, and only a single `GetItem` at fire time:

```json
{
  "transferId": "t-abc123",
  "targetTimeoutAt": 1715385600,
  "connectorId": "c-1234567890abcdef0",
  "expectedFiles": 10,
  "transferDirection": "SEND",
  "metadata": "{\"orderId\":\"ORD-001\",\"customer\":\"ACME\"}",
  "filePaths": ["/uploads/file1.csv", "/uploads/file2.csv", "/uploads/file3.csv"]
}
```

| Field | Source | Purpose |
|-------|--------|---------|
| `transferId` | Master record `jobId` | DynamoDB primary key for `GetItem` at fire time |
| `targetTimeoutAt` | Master record `batchTimeoutAt` | Epoch target for hop/fire decision |
| `connectorId` | Master record `connectorId` | Timeout event assembly (no DDB read needed) |
| `expectedFiles` | Master record `expectedFiles` | Timeout event assembly + guard check |
| `transferDirection` | Master record `transferDirection` | Determines timeout event `detail-type` |
| `metadata` | Master record `metadata` | Timeout event `_helper_metadata` field |
| `filePaths` | Master record `filePaths` | Placeholder assembly for timed-out files |

---

## Timeout Event Format

### Detail-Types

| detail-type | Trigger |
|-------------|---------|
| `SFTP Connector Whole File Send Transfer Timed Out - CUSTOM` | Timeout elapsed for a send transfer |
| `SFTP Connector Whole File Retrieve Transfer Timed Out - CUSTOM` | Timeout elapsed for a retrieve transfer |

**Source:** `custom.sftp-connector-helper`

### New `status-code` Value

| Value | Meaning |
|-------|---------|
| `TIMED_OUT` | Timeout elapsed; not all files resolved. Some may have completed/failed, others are missing. |

### Event Structure

```json
{
  "version": "0",
  "source": "custom.sftp-connector-helper",
  "detail-type": "SFTP Connector Whole File Send Transfer Timed Out - CUSTOM",
  "detail": {
    "transfer-id": "t-abc123",
    "connector-id": "c-1234567890abcdef0",
    "status-code": "TIMED_OUT",
    "file-count": 10,
    "completed-count": 7,
    "failed-count": 1,
    "timed-out-count": 2,
    "_helper_metadata": { "orderId": "ORD-001", "customer": "ACME" },
    "files": [
      {
        "file-transfer-id": "ft-001",
        "status-code": "COMPLETED",
        "file-path": "/uploads/file1.csv",
        "transfer-id": "t-abc123",
        "connector-id": "c-1234567890abcdef0",
        "_helper_metadata": { "orderId": "ORD-001", "customer": "ACME" }
      },
      {
        "file-transfer-id": "ft-002",
        "status-code": "FAILED",
        "file-path": "/uploads/file2.csv",
        "failure-message": "Permission denied",
        "transfer-id": "t-abc123",
        "connector-id": "c-1234567890abcdef0",
        "_helper_metadata": { "orderId": "ORD-001", "customer": "ACME" }
      },
      {
        "file-transfer-id": "unknown",
        "status-code": "TIMED_OUT",
        "file-path": "/uploads/file3.csv",
        "_helper_metadata": { "orderId": "ORD-001", "customer": "ACME" }
      },
      {
        "file-transfer-id": "unknown",
        "status-code": "TIMED_OUT",
        "file-path": "/uploads/file4.csv",
        "_helper_metadata": { "orderId": "ORD-001", "customer": "ACME" }
      }
    ]
  }
}
```

### `files[]` Array Composition

| Entry type | `status-code` | `file-transfer-id` | Source |
|-----------|---------------|---------------------|--------|
| Resolved (success) | `COMPLETED` | Actual ID from Transfer Family | `fileStatuses` map in DDB |
| Resolved (failure) | `FAILED` | Actual ID from Transfer Family | `fileStatuses` map in DDB |
| Timed out (missing) | `TIMED_OUT` | `"unknown"` | `filePaths` list minus resolved paths |

### Field Definitions

| Field | Type | Description |
|-------|------|-------------|
| `file-count` | Number | Total expected files (from `expectedFiles`) |
| `completed-count` | Number | Files with `status-code: COMPLETED` |
| `failed-count` | Number | Files with `status-code: FAILED` |
| `timed-out-count` | Number | `file-count - completed-count - failed-count` |

---

## API Surface

### `FileTransferOptions` — New Field

```java
FileTransferOptions options = FileTransferOptions.builder()
    .emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY)
    .batchTimeout(Duration.ofHours(1))  // default when emissionMode ≠ INDIVIDUAL
    .build();
```

| Field | Type | Default | Constraints |
|-------|------|---------|-------------|
| `batchTimeout` | `Duration` | 1 hour (when batch mode active) | ≥ 15 min or `Duration.ZERO` (disabled). Throws `IllegalArgumentException` if 0 < value < 15 min. Ignored when `emissionMode == INDIVIDUAL_FILE_EVENTS_ONLY`. |

### Validation Rules

```java
// In FileTransferOptions.Builder.build():
if (emissionMode != EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY) {
    if (batchTimeout == null) {
        batchTimeout = Duration.ofHours(1); // default
    }
    if (!batchTimeout.isZero() && batchTimeout.toSeconds() < 900) {
        throw new IllegalArgumentException(
            "batchTimeout must be >= 15 minutes or Duration.ZERO (disabled), got: " + batchTimeout);
    }
}

// In SftpConnectorHelper.startFileTransfer(), after resolving effective options:
// Validate batchTimeout < effective TTL (ttlDuration + 1h master stagger)
if (!effectiveBatchTimeout.isZero()) {
    long effectiveTtlSeconds = ttlDuration.toSeconds() + 3600; // master record stagger
    if (effectiveBatchTimeout.toSeconds() >= effectiveTtlSeconds) {
        throw new IllegalArgumentException(
            "batchTimeout (" + effectiveBatchTimeout + ") must be less than effective TTL ("
            + Duration.ofSeconds(effectiveTtlSeconds) + " = ttlDuration + 1h master stagger). "
            + "Otherwise the DynamoDB record may expire before the timeout fires.");
    }
}
```

### Effective Behavior Matrix

| `emissionMode` | `batchTimeout` | Timeout event? |
|----------------|---------------|----------------|
| `INDIVIDUAL_FILE_EVENTS_ONLY` | (any) | No — no batch tracking |
| `WHOLE_TRANSFER_COMPLETION_ONLY` | default (1h) | Yes |
| `WHOLE_TRANSFER_COMPLETION_ONLY` | `Duration.ZERO` | No — disabled |
| `WHOLE_TRANSFER_COMPLETION_ONLY` | `Duration.ofMinutes(30)` | Yes, at 30 min |
| `INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION` | default (1h) | Yes |
| `INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION` | `Duration.ZERO` | No — disabled |

---

## Master Record Schema Changes

### New Attributes

| Attribute | Type | Written by | Description |
|-----------|------|-----------|-------------|
| `batchTimeoutAt` | Number | Java helper | Unix epoch = creation time + `batchTimeout` duration. Absent when timeout disabled. |
| `filePaths` | List\<String\> | Java helper | Ordered list of file paths from the original `sendFilePaths()` or `retrieveFilePaths()`. Required for placeholder assembly in timeout events. |
| `batchTimeoutScheduled` | Boolean | Joiner | Dedup marker: prevents duplicate SQS sends on DynamoDB Stream replay. |
| `batchTimeoutPublished` | Boolean | Joiner (SQS path) | Dedup marker: prevents duplicate timeout events. Also suppresses orphan alerts. |

### Java Helper `UpdateExpression` (Master Record)

When `batchTimeout` is non-zero and `emissionMode ≠ INDIVIDUAL_FILE_EVENTS_ONLY`:

```
SET metadata = :m, #t = :t, emissionMode = :em, expectedFiles = :ef,
    transferDirection = :td, connectorId = :cid, fileStatuses = :fs,
    batchTimeoutAt = :bta, filePaths = :fp
```

With values:
- `:bta` = `Instant.now().getEpochSecond() + batchTimeout.getSeconds()`
- `:fp` = `request.sendFilePaths()` or `request.retrieveFilePaths()` (as a DynamoDB List)

When `batchTimeout` is `Duration.ZERO`, `batchTimeoutAt` and `filePaths` are **omitted** from the expression.

### Complete Master Record Attribute Table

| Attribute | Written by | Existing? | Purpose |
|-----------|-----------|-----------|---------|
| `jobId` (PK) | Java helper | Yes | = transferId |
| `metadata` | Java helper | Yes | Business metadata JSON |
| `ttl` | Java helper | Yes | Record expiry (base + 1h stagger) |
| `emissionMode` | Java helper | Yes | Batch event emission mode |
| `expectedFiles` | Java helper | Yes | File count for batch tracking |
| `transferDirection` | Java helper | Yes | SEND or RETRIEVE |
| `connectorId` | Java helper | Yes | For event assembly |
| `fileStatuses` | Joiner | Yes | Per-file completion map |
| `resolvedCount` | Joiner | Yes | Atomic counter |
| `batchEventPublished` | Joiner | Yes | Dedup: normal batch event |
| `batchTimeoutAt` | Java helper | **New** | Epoch target for timeout |
| `filePaths` | Java helper | **New** | Original file paths for placeholder assembly |
| `batchTimeoutScheduled` | Joiner | **New** | Dedup: SQS message send |
| `batchTimeoutPublished` | Joiner (SQS path) | **New** | Dedup: timeout event publication |

---

## DynamoDB Access Pattern

| When | Operation | Details |
|------|-----------|---------|
| Joiner schedules timeout (pre-check) | `GetItem(PK=transferId)` projected | Checks `batchTimeoutScheduled`; skips if already set |
| Joiner schedules timeout (dedup marker) | `UpdateItem(PK=transferId)` conditional | Sets `batchTimeoutScheduled=true` after SQS send; silent on `ConditionalCheckFailedException` |
| Each hop (up to 3 intermediate) | None | Pure SQS re-enqueue from message body |
| Fire time — guard check | `GetItem(PK=transferId)` | Projected: `resolvedCount`, `batchEventPublished`, `batchTimeoutPublished`, `fileStatuses` |
| Fire time — dedup marker | `UpdateItem(PK=transferId)` conditional | Sets `batchTimeoutPublished=true`; `attribute_not_exists(batchTimeoutPublished)` |

**Total DDB cost per timeout event: 2 RCU + 2 WCU** (1 RCU pre-check + 1 WCU dedup marker at T+0, 1 RCU + 1 WCU at fire time).

**During hops: zero DDB cost.**

---

## Joiner Lambda Changes

### Handler Dispatch (Two Event Sources)

The Joiner Lambda receives events from two sources:
1. **DynamoDB Streams** (via EventBridge Pipe) — existing path
2. **SQS Timeout Queue** — new path

Dispatch by event shape:

```python
from batch_timeout import process_timeout_message

def lambda_handler(event, context):
    """Process events from two sources:
    - EventBridge Pipe (DynamoDB Streams): delivers a list of stream records directly
    - SQS Event Source Mapping: delivers {"Records": [{"eventSource": "aws:sqs", ...}]}
    """
    if isinstance(event, list):
        # EventBridge Pipe path (existing) — batch_size=1 typical
        for stream_record in event:
            _process_record(stream_record)
    elif "Records" in event:
        # SQS event source mapping path (new)
        for record in event["Records"]:
            body = json.loads(record["body"])
            process_timeout_message(table, sqs, TIMEOUT_QUEUE_URL, events, EVENT_BUS_NAME, body)
    else:
        # Single stream record fallback
        _process_record(event)
```

### New: Schedule Timeout (Master INSERT Path)

Triggered inside `_metadata_copy_dispatch()` in `handler.py`, in the master record detection branch (`has metadata, no eventResult, no transferId`), immediately after `fan_out_metadata_to_per_file_records(...)` returns:

```python
# In handler.py — modified _metadata_copy_dispatch(), master record branch:
from batch_timeout import schedule_batch_timeout

def _metadata_copy_dispatch(new_image: dict, job_id: str) -> bool:
    ...
    # Master detection: has metadata, no eventResult, no transferId
    if has_metadata and not has_event_result and not has_transfer_id:
        log_structured("INFO", "Master record detected, fanning out metadata", job_id=job_id)
        fan_out_metadata_to_per_file_records(table, job_id, new_image["metadata"], events, EVENT_BUS_NAME)

        # Schedule batch timeout after fan-out (new)
        if new_image.get("expectedFiles") and new_image.get("batchTimeoutAt"):
            schedule_batch_timeout(table, sqs, TIMEOUT_QUEUE_URL, new_image)

        return True
    ...
```

The timeout logic lives in a dedicated `batch_timeout.py` module:

```python
# batch_timeout.py — Batch timeout scheduling, hop/fire processing, and event assembly.

import json
import time

from log_util import log_structured
from publish import publish_batch_completion_event


def schedule_batch_timeout(table, sqs_client, queue_url: str, master_image: dict) -> None:
    """Send delayed SQS message to trigger timeout check. Idempotent via conditional write.

    Ordering: SQS send BEFORE dedup marker write. This ensures that if the Lambda crashes
    after the dedup marker but before the SQS send, the schedule can be retried on stream
    replay. Duplicate SQS messages are harmless — the fire logic is fully idempotent
    (conditional write on batchTimeoutPublished + resolvedCount guard).
    """
    job_id = master_image["jobId"]

    # Pre-check: skip if already scheduled (avoids unnecessary SQS sends on replay)
    resp = table.get_item(
        Key={"jobId": job_id},
        ProjectionExpression="batchTimeoutScheduled",
        ConsistentRead=True
    )
    if resp.get("Item", {}).get("batchTimeoutScheduled"):
        return  # Silent — already scheduled

    target_time = int(master_image["batchTimeoutAt"])
    remaining = max(target_time - int(time.time()), 1)
    delay = min(remaining, 900)

    # Send SQS message FIRST — duplicates are harmless
    sqs_client.send_message(
        QueueUrl=queue_url,
        MessageBody=json.dumps({
            "transferId": job_id,
            "targetTimeoutAt": target_time,
            "connectorId": master_image.get("connectorId", "unknown"),
            "expectedFiles": int(master_image["expectedFiles"]),
            "transferDirection": master_image.get("transferDirection", "SEND"),
            "metadata": master_image["metadata"],
            "filePaths": master_image.get("filePaths", [])
        }),
        DelaySeconds=delay
    )

    # Dedup marker AFTER successful SQS send — prevents redundant sends on future replays
    try:
        table.update_item(
            Key={"jobId": job_id},
            UpdateExpression="SET batchTimeoutScheduled = :t",
            ConditionExpression="attribute_not_exists(batchTimeoutScheduled)",
            ExpressionAttributeValues={":t": True}
        )
    except table.meta.client.exceptions.ConditionalCheckFailedException:
        pass  # Silent — concurrent schedule won; our SQS message is a harmless duplicate
```

### New: Timeout Message Processing (SQS Path)

```python
# batch_timeout.py (continued)

def process_timeout_message(table, sqs_client, queue_url: str, events_client, bus_name: str, body: dict) -> None:
    """Process a timeout message: hop or fire."""
    transfer_id = body["transferId"]
    target_time = body["targetTimeoutAt"]
    now = int(time.time())

    # Hop: not yet time
    if now < target_time:
        remaining = min(target_time - now, 900)
        sqs_client.send_message(
            QueueUrl=queue_url,
            MessageBody=json.dumps(body),
            DelaySeconds=remaining
        )
        return

    # Fire time — single GetItem by PK (projected fields only)
    resp = table.get_item(
        Key={"jobId": transfer_id},
        ProjectionExpression="resolvedCount, batchEventPublished, batchTimeoutPublished, fileStatuses",
        ConsistentRead=True
    )
    item = resp.get("Item")
    if not item:
        # Record TTL'd before timeout fired — publish degraded event from SQS message body.
        # All immutable data (transferId, connectorId, metadata, filePaths) is in the message.
        # All files are reported as TIMED_OUT since we have no fileStatuses.
        detail, detail_type = _assemble_timeout_event(body, {"fileStatuses": {}})
        publish_batch_completion_event(events_client, bus_name, detail, detail_type)
        log_structured("WARN", "Published degraded batch timeout event (record TTL'd before fire)",
                       transfer_id=transfer_id, expected_files=body["expectedFiles"])
        return

    # Guard: already handled?
    if item.get("batchEventPublished") or item.get("batchTimeoutPublished"):
        return

    # Guard: all files resolved between last hop and now?
    resolved = int(item.get("resolvedCount", 0))
    expected = body["expectedFiles"]
    if resolved >= expected:
        return  # Normal batch event will/did fire

    # Publish-before-marker pattern: publish the event FIRST, then write the dedup marker.
    # This ensures that if Lambda crashes after publishing but before the marker write,
    # the event is delivered (at-least-once) rather than lost. On retry, the conditional
    # write may succeed again and produce a duplicate event — acceptable given the
    # at-least-once delivery contract and consumer idempotency requirement.

    # Assemble and publish timeout event FIRST
    detail, detail_type = _assemble_timeout_event(body, item)
    publish_batch_completion_event(events_client, bus_name, detail, detail_type)

    # Dedup marker AFTER publish — conditional write (guards against concurrent fire)
    try:
        table.update_item(
            Key={"jobId": transfer_id},
            UpdateExpression="SET batchTimeoutPublished = :t",
            ConditionExpression="attribute_not_exists(batchTimeoutPublished) AND resolvedCount < :expected",
            ExpressionAttributeValues={":t": True, ":expected": expected}
        )
    except table.meta.client.exceptions.ConditionalCheckFailedException:
        pass  # Marker write failed — event already published (at-least-once is acceptable)

    log_structured("INFO", "Published batch timeout event",
                   transfer_id=transfer_id,
                   resolved_count=resolved,
                   expected_files=expected,
                   timed_out_count=expected - resolved)
```

### New: Timeout Event Assembly

```python
# batch_timeout.py (continued)

def _assemble_timeout_event(msg: dict, item: dict) -> tuple[dict, str]:
    """Assemble timeout event detail from SQS message + DDB guard read."""
    metadata = json.loads(msg["metadata"])
    file_statuses = item.get("fileStatuses", {})
    all_file_paths = msg.get("filePaths", [])

    # Resolved files from fileStatuses (exclude _init sentinel)
    resolved_files = []
    resolved_paths = set()
    for key, entry in file_statuses.items():
        if key == "_init" or not isinstance(entry, dict):
            continue
        if not entry.get("fileTransferId"):
            continue
        detail = json.loads(entry.get("eventDetail", "{}"))
        detail["_helper_metadata"] = metadata
        resolved_files.append(detail)
        resolved_paths.add(entry.get("filePath", ""))

    # Placeholder entries for timed-out files
    for path in all_file_paths:
        if path not in resolved_paths:
            resolved_files.append({
                "file-transfer-id": "unknown",
                "status-code": "TIMED_OUT",
                "file-path": path,
                "_helper_metadata": metadata
            })

    completed = sum(1 for f in resolved_files if f.get("status-code") == "COMPLETED")
    failed = sum(1 for f in resolved_files if f.get("status-code") not in ("COMPLETED", "TIMED_OUT"))
    timed_out = sum(1 for f in resolved_files if f.get("status-code") == "TIMED_OUT")

    direction = msg["transferDirection"]
    detail_type = (
        "SFTP Connector Whole File Send Transfer Timed Out - CUSTOM" if direction == "SEND"
        else "SFTP Connector Whole File Retrieve Transfer Timed Out - CUSTOM"
    )

    return {
        "transfer-id": msg["transferId"],
        "connector-id": msg["connectorId"],
        "status-code": "TIMED_OUT",
        "file-count": msg["expectedFiles"],
        "completed-count": completed,
        "failed-count": failed,
        "timed-out-count": timed_out,
        "_helper_metadata": metadata,
        "files": resolved_files
    }, detail_type
```

---

## Mutual Exclusion

Normal batch completion and timeout events are mutually exclusive. Whichever fires first suppresses the other.

### Guard Logic

| Location | Check | Action on conflict |
|----------|-------|-------------------|
| `_check_and_publish_batch` (normal batch path) | Conditional write: `attribute_not_exists(batchEventPublished) AND attribute_not_exists(batchTimeoutPublished)` | `ConditionalCheckFailedException` → skip publication |
| `_process_timeout_message` (timeout path) | `item.get("batchEventPublished")` | Skip timeout event publication |
| `_process_timeout_message` (timeout conditional write) | `attribute_not_exists(batchTimeoutPublished) AND resolvedCount < :expected` | Reject write if already published or all files resolved concurrently |

**`_check_and_publish_batch` modification:**

```python
def _check_and_publish_batch(...) -> None:
    if mode == "INDIVIDUAL_FILE_EVENTS_ONLY":
        return
    if resolved_count != expected_files:
        return
    if batch_published:
        return

    # Mutual exclusion via conditional write — prevents race with timeout path.
    # This replaces the previous unconditional best-effort marker.
    try:
        table.update_item(
            Key={"jobId": transfer_id},
            UpdateExpression="SET batchEventPublished = :t",
            ConditionExpression="attribute_not_exists(batchEventPublished) AND attribute_not_exists(batchTimeoutPublished)",
            ExpressionAttributeValues={":t": True}
        )
    except table.meta.client.exceptions.ConditionalCheckFailedException:
        return  # Timeout event already fired, or duplicate — mutual exclusion

    # Conditional write succeeded — safe to publish
    ...
```

### Race Condition Handling

Both paths use conditional writes as dedup markers:
- Normal batch: sets `batchEventPublished = true` (conditional: `attribute_not_exists(batchEventPublished) AND attribute_not_exists(batchTimeoutPublished)`)
- Timeout: sets `batchTimeoutPublished = true` (conditional: `attribute_not_exists(batchTimeoutPublished) AND resolvedCount < :expected`)

The timeout path's conditional write includes a `resolvedCount < :expected` guard. This closes the race where the last file resolves between the timeout checker's `GetItem` read and its conditional write:

1. Timeout checker reads `resolvedCount=9`, `expectedFiles=10` → proceeds
2. Last file arrives concurrently, Joiner increments `resolvedCount` to 10
3. Timeout checker's conditional write fails (`resolvedCount < 10` is now false) → skips timeout event
4. Normal batch path fires normally

In the reverse direction, if the timeout conditional write succeeds first (`batchTimeoutPublished=true`), the normal batch path's conditional write fails (`attribute_not_exists(batchTimeoutPublished)` is false) and skips publication.

**Result:** At most one event type is published per transfer. Both paths use DynamoDB conditional writes as the serialization point — no TOCTOU race is possible.

### Scenario Matrix

| Scenario | Normal batch event | Timeout event |
|----------|-------------------|---------------|
| All files resolve before timeout | ✓ Published | ✗ Skipped (guard: `batchEventPublished=true`) |
| Timeout fires before all files resolve | ✗ Skipped (guard: `batchTimeoutPublished=true`) | ✓ Published |
| All files resolve during final hop (race) | At most one wins | At most one wins |
| No files ever resolve | ✗ Never triggered | ✓ Published (`timed-out-count = file-count`) |

---

## Orphan Detection Integration

### Modified Behavior

When the master record expires via DynamoDB TTL (REMOVE event), the orphan detection branch currently checks:
- Master record with `metadata`, no `eventResult`, no `transferId` → queries GSI for per-file records; if none exist → orphan alert

**New guard:** If `batchTimeoutPublished = true` on the expired record, skip the orphan alert entirely. The consumer was already notified via the timeout event.

```python
# In orphan_detection.py, master record branch:
def detect_orphan(table, old_image, job_id, sns, topic_arn, cloudwatch):
    ...
    # Master record branch
    if has_metadata and not has_event_result and not has_transfer_id:
        if old_image.get("batchTimeoutPublished"):
            log_structured("INFO", "Skipping orphan alert — timeout event already published",
                           job_id=job_id)
            return
        # existing orphan detection logic continues...
```

---

## Infrastructure Changes (CDK)

### New Resources

| Resource | Type | Configuration |
|----------|------|---------------|
| `sftp-connector-helper-timeout` | SQS Queue (Standard) | `MessageRetentionPeriod=86400` (24h, matches DDB TTL) |
| `sftp-connector-helper-timeout-dlq` | SQS Queue (Standard) | `MessageRetentionPeriod=86400`, `maxReceiveCount=3` on redrive policy |
| SQS Event Source Mapping | Lambda ESM | Source: timeout queue, `batch_size=1`, function: Joiner Lambda |

### IAM Permissions (Joiner Lambda — additions)

| Action | Resource | Purpose |
|--------|----------|---------|
| `sqs:SendMessage` | Timeout queue ARN | Schedule timeout + re-enqueue hops |
| `sqs:ReceiveMessage` | Timeout queue ARN | Event source mapping |
| `sqs:DeleteMessage` | Timeout queue ARN | Event source mapping |
| `sqs:GetQueueAttributes` | Timeout queue ARN | Event source mapping |

### Environment Variables (Joiner Lambda — addition)

| Variable | Value | Purpose |
|----------|-------|---------|
| `TIMEOUT_QUEUE_URL` | Queue URL | Target for `SendMessage` calls |

### No New CDK Construct Props

The timeout infrastructure is always deployed. Per-transfer opt-out is via `batchTimeout(Duration.ZERO)` in the Java API. No CDK-level toggle.

---

## Idempotency Contract (Extended)

| Component | Write Type | Condition | Behavior on Conflict |
|-----------|-----------|-----------|---------------------|
| Joiner (schedule SQS) | `GetItem` pre-check + `UpdateItem` after send | `attribute_not_exists(batchTimeoutScheduled)` | Pre-check skips early; conditional write after SQS is best-effort dedup (duplicates harmless) |
| Joiner (timeout dedup) | `UpdateItem` | `attribute_not_exists(batchTimeoutPublished) AND resolvedCount < :expected` | Skip publication — already published or all files resolved concurrently |
| Joiner (normal batch dedup) | `UpdateItem` | `attribute_not_exists(batchEventPublished) AND attribute_not_exists(batchTimeoutPublished)` | Skip publication — already published or timeout won the race |
| SQS at-least-once delivery | Re-delivery | Timeout Checker is idempotent | Duplicate hops are harmless; duplicate fire attempts blocked by conditional write |

### Failure Modes

| Failure | Impact | Recovery |
|---------|--------|----------|
| Joiner crashes after SQS send, before `batchTimeoutScheduled` write | Duplicate SQS message on stream replay | Harmless — fire logic is idempotent (in-memory guards + conditional write) |
| Joiner crashes before SQS send (pre-check passed) | No SQS message sent | Stream replay retries; pre-check re-reads DDB and proceeds |
| SQS message lost (extremely rare) | Timeout never fires | Orphan detection at TTL expiry (25h) acts as backstop |
| DynamoDB Stream event lost/DLQ'd by Pipe | `schedule_batch_timeout()` never called; no SQS message sent | Orphan detection at TTL expiry (25h) acts as backstop. Observable via Pipe IteratorAge alarm and Pipe DLQ depth. |
| Timeout Checker crashes after publishing, before `batchTimeoutPublished` write | Duplicate timeout event on SQS retry | Consumer idempotency handles this (at-least-once delivery contract) |
| Timeout Checker crashes before publishing (guards passed) | No event published, no marker written | SQS redelivers message; guards re-evaluated; event published on retry |
| DDB throttling on `GetItem` at fire time | Lambda retry (SQS visibility timeout) | Message redelivered; re-evaluates guards |

---

## Stream Loop Prevention

The `batchTimeoutScheduled` write to the master record generates a DynamoDB Stream MODIFY event. This is handled by the **existing** stream loop guard in `handler.py`:

```python
# Existing guard (from WHOLE_TRANSFER_COMPLETION spec):
if "metadata" in old_image and "fileStatuses" in new_image and not _has_both_fields(new_image):
    log_structured("INFO", "Skipping batch-tracking MODIFY on master record", job_id=job_id)
    return
```

The `batchTimeoutScheduled` write does not add `fileStatuses` to the record (it's already present from the initial INSERT). However, the MODIFY event's NewImage will contain `fileStatuses` (already written), so the existing guard catches it. No additional loop prevention needed.

**Edge case:** If `batchTimeoutScheduled` is the first MODIFY after the INSERT (before any file arrives), `fileStatuses` is present (initialized with `_init` sentinel by the Java helper). The guard condition holds.

---

## Cost Impact

| Component | Per batch transfer | At rest (no transfers) |
|-----------|--------------------|------------------------|
| SQS messages | ~4 sends × $0.0000004 = $0.0000016 | $0 |
| Lambda invocations (hops) | ~3 × 128MB × 100ms ≈ negligible | $0 |
| Lambda invocation (fire) | 1 × 128MB × 200ms ≈ negligible | $0 |
| DDB (schedule pre-check + dedup) | 1 RCU + 1 WCU | $0 |
| DDB (fire) | 1 RCU + 1 WCU | $0 |
| SQS queue (idle) | $0 | $0 |

**Total per timeout: < $0.001.** No cost when no batch transfers are in-flight.

---

## Consumer Guidance

### EventBridge Rule (Prefix Matching)

To receive both normal batch completion and timeout events:

```json
{
  "source": ["custom.sftp-connector-helper"],
  "detail-type": [
    { "prefix": "SFTP Connector Whole File Send Transfer" }
  ]
}
```

This matches:
- `SFTP Connector Whole File Send Transfer Completed - CUSTOM`
- `SFTP Connector Whole File Send Transfer Timed Out - CUSTOM`

### Consumer Pattern

```python
def lambda_handler(event, context):
    detail = event["detail"]
    batch_status = detail["status-code"]
    meta = detail["_helper_metadata"]

    if batch_status == "ALL_COMPLETED":
        trigger_downstream(meta["orderId"])
    elif batch_status in ("PARTIAL_FAILURE", "ALL_FAILED"):
        handle_failures(meta["orderId"], detail["files"])
    elif batch_status == "TIMED_OUT":
        timed_out_files = [f for f in detail["files"] if f["status-code"] == "TIMED_OUT"]
        handle_timeout(meta["orderId"], timed_out_files, detail["timed-out-count"])
```

---

## Impact Summary

| Component | Change | Complexity |
|-----------|--------|-----------|
| Java helper (`FileTransferOptions`) | Add `batchTimeout` field with validation | Low |
| Java helper (master record write) | Add `batchTimeoutAt` + `filePaths` to `UpdateExpression` | Low |
| Joiner Lambda (`handler.py`) | Add SQS dispatch in `lambda_handler`; call `schedule_batch_timeout` from `_metadata_copy_dispatch` after fan-out | Low |
| Joiner Lambda (`batch_timeout.py`) | **New module** — `schedule_batch_timeout`, `process_timeout_message` (hop/fire), `_assemble_timeout_event` | Medium |
| Joiner Lambda (`file_transfer_mgmt.py`) | Add `batchTimeoutPublished` guard in `_check_and_publish_batch` | Low |
| Joiner Lambda (`orphan_detection.py`) | Add `batchTimeoutPublished` guard to skip alert | Low |
| CDK construct | Add SQS queue + DLQ + event source mapping + IAM + env var | Low |
| Event schema | New detail-types, new `status-code` value, new `timed-out-count` field | Low |

### Joiner Code Structure (Updated)

| File | Change |
|------|--------|
| `handler.py` | **Modified** — SQS/DDB stream dispatch; calls `schedule_batch_timeout` from `_metadata_copy_dispatch` after fan-out |
| `batch_timeout.py` | **New** — `schedule_batch_timeout`, `process_timeout_message`, `_assemble_timeout_event` |
| `file_transfer_mgmt.py` | **Modified** — `batchTimeoutPublished` guard in `_check_and_publish_batch` |
| `orphan_detection.py` | **Modified** — `batchTimeoutPublished` guard |
| `publish.py` | Unchanged (reuses existing `publish_batch_completion_event`) |

---

## Relationship to Existing Specs

This specification **extends** [WHOLE_TRANSFER_COMPLETION.md](WHOLE_TRANSFER_COMPLETION.md):
- Adds a new event type (timeout) alongside the existing batch completion event
- Reuses the same `fileStatuses` tracking infrastructure
- Adds mutual exclusion between the two batch-level event types
- Resolves the "Batch timeout / stuck detection" item listed as "Deferred" in that spec's Open Items
