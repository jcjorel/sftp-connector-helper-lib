# Specification: Whole Transfer Completion Event

## Status: DRAFT

## Problem

Transfer Family emits one event per file with no batch-level completion signal. Users must build their own tracking infrastructure (counter table, atomic increments, completion detection) to know when all files in a multi-file transfer have been processed. This is boilerplate pain every multi-file user faces.

## Solution

The framework emits a custom completion event when all per-file events for a given `transfer-id` have been received:
- `"SFTP Connector Whole File Send Transfer Completed - CUSTOM"` (send direction)
- `"SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM"` (retrieve direction) The user controls event emission behavior via an options builder parameter on `startFileTransfer`.

## Scope

**In scope:**
- Batch completion detection for `startFileTransfer` (both send and retrieve directions)
- Options builder with emission mode enum
- Idempotent master record tracking with at-least-once event delivery
- New custom event type on the dedicated EventBridge bus
- Single-file transfers treated as batch of 1 (no special casing)

**Out of scope (deferred):**
- Batch timeout / stuck detection (future generic API timeout feature)
- Progress events (e.g., 50% complete)

## Design Principles

1. **Backward compatible** — Default behavior unchanged; feature is opt-in
2. **At-least-once delivery** — Events may duplicate but never be lost
3. **Idempotent state updates** — Safe under EventBridge at-least-once redelivery
4. **No branching by direction** — Send and retrieve use identical implementation
5. **Single file = batch of 1** — No special casing
6. **Metadata size limit: 8 KB** — Maximum metadata payload reduced from 25 KB to 8 KB to maintain safety margins for master record storage (metadata × N files in `fileStatuses` + overhead must fit within DynamoDB's 400 KB item limit)
7. **Consistent reads on master record** — All `GetItem` calls on the master record use `ConsistentRead=true` to ensure the Joiner always sees the latest state (critical for `batchEventPublished` and `resolvedCount` accuracy)

---

## API Surface

### Enum

```java
public enum EventEmissionMode {
    INDIVIDUAL_FILE_EVENTS_ONLY,
    INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION,
    WHOLE_TRANSFER_COMPLETION_ONLY
}
```

### Options Builder

```java
public class FileTransferOptions {
    private final EventEmissionMode emissionMode;

    public static Builder builder() { ... }

    public static FileTransferOptions defaults() {
        return new FileTransferOptions(EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY);
    }

    public static class Builder {
        public Builder emissionMode(EventEmissionMode mode) { ... }
        public FileTransferOptions build() { ... }
    }
}
```

### Helper Method Signature

```java
// New overload (existing signatures unchanged)
SftpOperationResult<StartFileTransferResponse> startFileTransfer(
    StartFileTransferRequest request,
    String metadata,
    FileTransferOptions options
);
```

When `options` is not provided, behavior is identical to today (`INDIVIDUAL_FILE_EVENTS_ONLY`).

---

## Master Record Schema Changes

Existing master record fields are unchanged. New fields added:

| Attribute | Type | Description |
|-----------|------|-------------|
| `emissionMode` | String | Enum value controlling event publication |
| `expectedFiles` | Number | Count of files in the transfer (derived from `sendFilePaths` or `retrieveFilePaths` list size) |
| `transferDirection` | String | `SEND` or `RETRIEVE` — derived from whether `sendFilePaths` or `retrieveFilePaths` was used in the original request |

**Derivation logic in Java Helper** (using AWS SDK v2 `StartFileTransferRequest` inspection):

```java
boolean isSend = request.hasSendFilePaths() && !request.sendFilePaths().isEmpty();
String transferDirection = isSend ? "SEND" : "RETRIEVE";
int expectedFiles = isSend ? request.sendFilePaths().size() : request.retrieveFilePaths().size();
```

These SDK methods (`hasSendFilePaths()`, `hasRetrieveFilePaths()`, `sendFilePaths()`, `retrieveFilePaths()`) are available on the request object in memory — no network call required.
| `fileStatuses` | Map | `{ "file-transfer-id": { "status": "...", "filePath": "...", "eventDetail": "..." } }` |
| `resolvedCount` | Number | Atomically incremented count of resolved files |
| `batchEventPublished` | Boolean | Best-effort dedup marker; `true` once batch event has been published |

**Note:** `fileStatuses` stores the enriched event detail per file so the batch event can be assembled without re-reading per-file records.

### When `emissionMode` is absent (legacy records)

The Joiner treats missing `emissionMode` as `INDIVIDUAL_FILE_EVENTS_ONLY` — current behavior preserved.

---

## Joiner Lambda — Batch-Aware Flow

The existing Joiner flow is extended. When a per-file stream event arrives and the master record contains `emissionMode` and `expectedFiles`:

```
1. Read master record with ConsistentRead=true (already happens today for metadata copy)

2. If emissionMode ∈ {INDIVIDUAL_FILE_EVENTS_ONLY, INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION}:
   → Publish individual enriched event (current behavior)

3. Conditional update master record:
     UpdateExpression:
       SET fileStatuses.#ftId = :fileEventDetail
       ADD resolvedCount :one
     ConditionExpression:
       attribute_not_exists(fileStatuses.#ftId)
     ReturnValues: ALL_NEW

   → On success: read resolvedCount AND batchEventPublished from ALL_NEW response
   → On ConditionalCheckFailedException: read resolvedCount AND batchEventPublished
     from the master record already fetched in step 1 (or re-read via GetItem)

4. If emissionMode ∈ {INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION, WHOLE_TRANSFER_COMPLETION_ONLY}
   AND resolvedCount == expectedFiles
   AND batchEventPublished == false:
   → Assemble batch completion event from fileStatuses map (available in ALL_NEW response)
   → Publish to dedicated EventBridge bus
   → SET batchEventPublished = true (unconditional, best-effort dedup marker)
```

Step 4 is a **single uniform guard** applied regardless of whether step 3 succeeded or failed. There is no first-time/retry branching.

**Note on `ReturnValues: ALL_NEW`:** This returns the complete item after the update, including `batchEventPublished` and the full `fileStatuses` map. This eliminates the need for a separate `GetItem` and provides an atomic snapshot of the master record state for both the step 4 guard and batch event assembly.

### Why This Guarantees At-Least-Once Delivery

- **Individual events**: Published **before** the conditional write (step 2 before step 3). If the Joiner crashes after publishing but before the conditional write succeeds, the stream retry re-publishes (duplicate, acceptable) and then the conditional write succeeds.
- **Batch event**: If the Joiner crashes after publishing the batch event but before writing `batchEventPublished = true`, the stream retries. The conditional write in step 3 fails (duplicate), but step 4 re-evaluates the guard: `resolvedCount == expectedFiles AND batchEventPublished == false` → re-publishes (duplicate, acceptable). Once `batchEventPublished` is written as `true`, subsequent retries skip step 4 entirely.
- The conditional write in step 3 prevents double-counting (`resolvedCount` never incremented twice for the same file).

### Why Duplicate Batch Events Are Bounded

The `batchEventPublished` flag is a **best-effort dedup marker**, not a correctness gate. It reduces unnecessary duplicate batch events on retries but does not prevent all duplicates (crash between publish and flag write). Consumers must be idempotent — consistent with EventBridge's at-least-once delivery contract.

### Concurrency Safety

DynamoDB `ADD` is atomic. Two concurrent conditional updates for different `file-transfer-id` values both succeed, but each sees a different `resolvedCount` in `UPDATED_NEW`. Typically only one invocation observes `resolvedCount == expectedFiles`. In the rare case where a retry also evaluates step 4, the `batchEventPublished` flag prevents redundant publication.

### Fan-Out Reconciliation (Fast Transfer Race)

Per-file events can arrive and be written to DynamoDB by the Event Writer **before** the Java Helper finishes writing the master record. In this case, the Joiner processes per-file stream events but finds no master record — it cannot perform metadata copy or batch tracking.

The existing architecture handles this: when the master record is eventually written, its own DDB Stream INSERT event triggers the Joiner's **fan-out path** (GSI query finds existing per-file records → copies metadata). The batch-aware extension must reconcile batch state during this same fan-out:

```
Fan-out path (triggered by master record INSERT):

1. Query GSI for all per-file records with this transfer-id
2. For each per-file record found:
   a. Copy metadata to per-file record (existing behavior)
   b. If per-file record has eventResult (already resolved):
      → Conditional update master record:
          SET fileStatuses.#ftId = :fileEventDetail
          ADD resolvedCount :one
          CONDITION: attribute_not_exists(fileStatuses.#ftId)
      → If emissionMode allows individual events:
          Publish individual enriched event

3. After processing all per-file records:
   If emissionMode allows batch
   AND resolvedCount == expectedFiles
   AND batchEventPublished == false:
   → Publish batch completion event
   → SET batchEventPublished = true
```

**Why this is safe:**
- The conditional update per file (`attribute_not_exists(fileStatuses.#ftId)`) prevents double-counting if the normal per-file path also runs concurrently.
- The same step 4 guard applies: only one code path (fan-out or per-file) will observe the final `resolvedCount` and publish the batch event.
- If all per-file events arrived before the master record, the fan-out path processes them all in a single invocation and may immediately publish the batch event.

**Ordering guarantee:** The fan-out path and the per-file path may run concurrently for the same file. The conditional write ensures exactly-once counting regardless of which path wins.

---

## Batch Completion Event Format

```json
{
  "version": "0",
  "source": "custom.sftp-connector-helper",
  "detail-type": "SFTP Connector Whole File Send Transfer Completed - CUSTOM",
  "detail": {
    "transfer-id": "t-abc123",
    "connector-id": "c-1234567890abcdef0",
    "status-code": "ALL_COMPLETED",
    "file-count": 3,
    "completed-count": 3,
    "failed-count": 0,
    "_helper_metadata": { "batchId": "BATCH-001", "customer": "ACME" },
    "files": [
      {
        "file-transfer-id": "ft-001",
        "file-path": "/uploads/a.csv",
        "status-code": "COMPLETED",
        "transfer-id": "t-abc123",
        "connector-id": "c-1234567890abcdef0",
        "_helper_metadata": { "batchId": "BATCH-001", "customer": "ACME" }
      },
      {
        "file-transfer-id": "ft-002",
        "file-path": "/uploads/b.csv",
        "status-code": "COMPLETED",
        "transfer-id": "t-abc123",
        "connector-id": "c-1234567890abcdef0",
        "_helper_metadata": { "batchId": "BATCH-001", "customer": "ACME" }
      },
      {
        "file-transfer-id": "ft-003",
        "file-path": "/uploads/c.csv",
        "status-code": "FAILED",
        "failure-message": "Permission denied",
        "transfer-id": "t-abc123",
        "connector-id": "c-1234567890abcdef0",
        "_helper_metadata": { "batchId": "BATCH-001", "customer": "ACME" }
      }
    ]
  }
}
```

### `status-code` Values (Batch Events)

| Value | Condition |
|-------|-----------|
| `ALL_COMPLETED` | Every file has `status-code: COMPLETED` |
| `ALL_FAILED` | Every file has `status-code: FAILED` |
| `PARTIAL_FAILURE` | Mix of COMPLETED and FAILED |

### `files[]` Array

Each entry is the **exact enriched event detail** the consumer would receive in individual event mode. This allows consumers to reuse per-file processing logic by iterating the array.

---

## Per-File Records — No Change

Per-file DynamoDB records are **always written** regardless of emission mode. The emission mode controls only which events are **published** to EventBridge. DynamoDB remains the source of truth.

Individual enriched events retain their existing `detail-type` (e.g., `"SFTP Connector File Send Completed"`) and format. No change for consumers using `INDIVIDUAL_FILE_EVENTS_ONLY`.

---

## Stream Loop Prevention

The Joiner's conditional update to the master record (step 3) generates a DynamoDB Stream MODIFY event. The Joiner must **not** re-trigger batch logic or fan-out on these updates.

**Problem:** The existing loop prevention (`OldImage already had both fields`) does not cover master record MODIFY events. A master record update (adding `fileStatuses`, `resolvedCount`) produces a MODIFY event where OldImage has `metadata` but no `eventResult` and no `transferId`. The existing `_metadata_copy_dispatch` would match this as "master record → fan out" and re-trigger fan-out on every update.

**Solution:** Add an explicit guard in `handler.py` for master record MODIFY events:

```python
# In _process_record, before _metadata_copy_dispatch:
if event_name == "MODIFY":
    old_image = unmarshal(old_image_raw)
    new_image = unmarshal(new_image_raw)

    # Guard: master record batch-tracking updates (fileStatuses/resolvedCount changed)
    # These are Joiner's own writes — skip to prevent loop.
    if "metadata" in old_image and "metadata" in new_image:
        if "fileStatuses" in new_image and not _has_both_fields(new_image):
            # Master record updated by batch tracking logic — not a join trigger
            return
```

**Criteria for identifying batch-tracking updates:**
- Event is MODIFY (not INSERT)
- OldImage already has `metadata` (master record was already initialized)
- NewImage has `fileStatuses` (batch tracking field present)
- NewImage does NOT have `eventResult` (not a per-file record completing)

This distinguishes three master record stream events:
1. **INSERT** (master record created by Helper) → triggers fan-out (existing behavior)
2. **MODIFY with `fileStatuses` change** (Joiner's own batch tracking write) → **skip** (new guard)
3. **MODIFY with `metadata` added** (metadata copy to per-file record) → handled by existing loop prevention

---

## Impact Summary

| Component | Change | Complexity |
|-----------|--------|-----------|
| Java Helper | New `FileTransferOptions` param; write `emissionMode` + `expectedFiles` to master record | Low |
| Master record schema | Add `emissionMode`, `expectedFiles`, `fileStatuses`, `resolvedCount`, `batchEventPublished` | Low |
| Joiner Lambda | New `file_transfer_mgmt.py` module + `handler.py` modified for stream loop guard and emission mode delegation | Medium |
| EventBridge bus | New `detail-type`s: `"SFTP Connector Whole File Send Transfer Completed - CUSTOM"` and `"SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM"` | Low |
| Per-file records | No change | None |
| Individual enriched events | No change in format (suppressed only when mode is `WHOLE_TRANSFER_COMPLETION_ONLY`) | None |
| CDK construct | No change | None |

### Joiner Code Structure

| File | Responsibility | Change |
|------|---------------|--------|
| `handler.py` | Stream event dispatch | **Modified** — adds stream loop guard for master record batch-tracking updates; delegates to `file_transfer_mgmt` for emission mode gating before publishing |
| `metadata_copy.py` | Metadata copy between records | **Modified** — fan-out path calls into `file_transfer_mgmt.reconcile_existing_files()` for batch state reconciliation |
| `file_transfer_mgmt.py` | **New** — per-file status tracking, `resolvedCount` update, batch event assembly, batch publication, fan-out reconciliation, emission mode gating |
| `publish.py` | EventBridge publishing | **Modified** — add batch event publish helper |
| `orphan_detection.py` | TTL-based orphan alerting | Unchanged |

### Handler Modification Detail

The existing `_process_record` flow currently publishes enriched events unconditionally when both `metadata` and `eventResult` are present. With this feature:

1. Before publishing, `handler.py` calls `file_transfer_mgmt.should_publish_individual_event(master_record)` which checks `emissionMode`.
2. If mode is `WHOLE_TRANSFER_COMPLETION_ONLY`, individual event publication is **skipped**.
3. The batch-aware flow (steps 1–4) is invoked via `file_transfer_mgmt.process_file_completion(...)` after the metadata copy step completes.

---

## Idempotency Contract (Extended)

| Component | Write Type | Condition | Behavior on Conflict |
|-----------|-----------|-----------|---------------------|
| Java Helper (metadata) | `UpdateItem` | `attribute_not_exists(metadata)` | Returns `MetadataAlreadyExists` |
| Java Helper (batch fields) | Written alongside metadata | Same condition as above | Same — atomic with metadata write |
| Joiner (per-file status) | `UpdateItem` | `attribute_not_exists(fileStatuses.#ftId)` | Skips increment; proceeds to step 4 guard |
| Joiner (batch event) | Publish to EventBridge | `resolvedCount == expectedFiles AND batchEventPublished == false` | Skips publication (already delivered) |
| Joiner (batch marker) | `UpdateItem` | Unconditional | Best-effort dedup marker; idempotent (writing `true` over `true`) |

---

## Consumer Guidance

### EventBridge Rule for Batch Events

```json
{
  "source": ["custom.sftp-connector-helper"],
  "detail-type": [
    "SFTP Connector Whole File Send Transfer Completed - CUSTOM",
    "SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM"
  ]
}
```

### Consumer Pattern

```python
def lambda_handler(event, context):
    detail = event["detail"]
    batch_status = detail["status-code"]
    meta = detail["_helper_metadata"]

    if batch_status == "ALL_COMPLETED":
        # All files delivered successfully
        trigger_downstream(meta["batchId"])
    elif batch_status == "PARTIAL_FAILURE":
        # Some files failed
        failed = [f for f in detail["files"] if f["status-code"] == "FAILED"]
        alert_on_failures(meta["batchId"], failed)
```

---

## Open Items

| Item | Status | Notes |
|------|--------|-------|
| Batch timeout detection | Deferred | Future generic API timeout feature |
| EventBridge event size limit | Non-issue | Max 10 files × ~1KB ≪ 256KB limit |
| `detail-type` naming convention | Decided | `"SFTP Connector Whole File Send Transfer Completed - CUSTOM"` / `"SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM"` |
