# Specification: Replace SftpOperationResult with Unchecked Exceptions

## Status

APPROVED — Ready for implementation.

## Motivation

The current API returns a sealed interface `SftpOperationResult<T>` with three variants (`Success`, `MetadataWriteFailed`, `MetadataAlreadyExists`). This forces every caller to pattern-match or cast the result even though:

1. The success path is the only one callers care about in practice (all integration tests immediately cast to `Success`).
2. The `MetadataAlreadyExists` variant is **unreachable** — each SDK call returns a globally unique job ID, and no other component writes the `metadata` attribute to DynamoDB.
3. The pattern is inconsistent with the rest of the API: SDK failures already propagate as unchecked `SdkException`, and validation errors throw `IllegalArgumentException`.

The refactoring simplifies the API to: **return the SDK response on success, throw on failure**.

## Design

### Exception Hierarchy

```
SftpConnectorHelperException (extends RuntimeException)
└── MetadataWriteException
```

#### `SftpConnectorHelperException`

- Abstract base class for all library-specific exceptions.
- Carries: `message`, `cause`.
- Extends `RuntimeException` (unchecked).

#### `MetadataWriteException`

- Thrown when the DynamoDB metadata write fails after a successful SDK call.
- Carries:
  - `jobId` (String) — the transfer/listing/move/delete ID from the SDK response.
  - `sdkResponse` (TransferResponse) — the raw SDK response (typed as the Transfer SDK base type), so callers can still access the job ID and proceed without metadata correlation if desired. Callers must cast to the concrete type matching the method they called (e.g., `(StartFileTransferResponse) e.getSdkResponse()`). This cast is always safe because the response type is determined by the method invoked. A generic approach (`MetadataWriteException<T>`) was considered but rejected: Java type erasure prevents catching parameterized exception types, making the generic parameter useless at the catch site. The JavaDoc for `getSdkResponse()` MUST document this casting requirement.
  - `cause` (Exception) — the underlying DynamoDB exception.
- The exception contract table MUST be included as JavaDoc on the `SftpConnectorHelper` class itself.

### Removed: `MetadataAlreadyExists`

Investigation confirmed this variant is unreachable:
- Transfer Family returns a unique job ID per SDK call.
- The event-writer Lambda writes `eventResult` only — never `metadata`.
- No other component writes `metadata`.
- The `attribute_not_exists(metadata)` condition in DynamoDB cannot fire through the library's API.

The DynamoDB condition expression is **retained** as a safety net. If it ever fires (indicating a catastrophic AWS bug), it is treated as a `MetadataWriteException` with a descriptive message.

### Method Signatures

**Before:**
```java
public SftpOperationResult<StartFileTransferResponse> startFileTransfer(
    StartFileTransferRequest request, String metadata)

public SftpOperationResult<StartFileTransferResponse> startFileTransfer(
    StartFileTransferRequest request, String metadata, FileTransferOptions options)

public SftpOperationResult<StartDirectoryListingResponse> startDirectoryListing(
    StartDirectoryListingRequest request, String metadata)

public SftpOperationResult<StartRemoteMoveResponse> startRemoteMove(
    StartRemoteMoveRequest request, String metadata)

public SftpOperationResult<StartRemoteDeleteResponse> startRemoteDelete(
    StartRemoteDeleteRequest request, String metadata)
```

**After:**
```java
public StartFileTransferResponse startFileTransfer(
    StartFileTransferRequest request, String metadata)

public StartFileTransferResponse startFileTransfer(
    StartFileTransferRequest request, String metadata, FileTransferOptions options)

public StartDirectoryListingResponse startDirectoryListing(
    StartDirectoryListingRequest request, String metadata)

public StartRemoteMoveResponse startRemoteMove(
    StartRemoteMoveRequest request, String metadata)

public StartRemoteDeleteResponse startRemoteDelete(
    StartRemoteDeleteRequest request, String metadata)
```

### Exception Contract

| Condition | Behavior |
|-----------|----------|
| Metadata validation fails | `IllegalArgumentException` (unchanged) |
| Request is null | `IllegalArgumentException` (unchanged) |
| SDK call fails (network, throttle, auth) | `SdkException` propagates (unchanged) |
| SDK returns null/empty job ID | `IllegalStateException` (unchanged) |
| DynamoDB write fails (throttle, error) | `MetadataWriteException` (NEW — was `MetadataWriteFailed`) |
| DynamoDB condition check fails | `MetadataWriteException` with message "Unexpected duplicate metadata for jobId: ..." (NEW — was `MetadataAlreadyExists`) |
| `batchTimeout >= effective TTL` | `IllegalArgumentException` (unchanged) |

### Caller Migration

**Before:**
```java
SftpOperationResult<StartFileTransferResponse> result = helper.startFileTransfer(request, metadata);
if (result instanceof SftpOperationResult.Success<StartFileTransferResponse> s) {
    System.out.println("Transfer: " + s.response().transferId());
} else if (result instanceof SftpOperationResult.MetadataWriteFailed<StartFileTransferResponse> f) {
    log.warn("Metadata lost for {}", f.jobId(), f.cause());
}
```

**After:**
```java
StartFileTransferResponse response = helper.startFileTransfer(request, metadata);
System.out.println("Transfer: " + response.transferId());
// MetadataWriteException propagates if metadata write fails
```

Callers who want to handle metadata failures gracefully:
```java
try {
    StartFileTransferResponse response = helper.startFileTransfer(request, metadata);
    System.out.println("Transfer: " + response.transferId());
} catch (MetadataWriteException e) {
    log.warn("Transfer {} started but metadata lost", e.getJobId(), e);
    // Transfer is in progress — proceed without enriched events
}
```

## Implementation Plan

### Phase 1: Create exception classes

| File | Action |
|------|--------|
| `SftpConnectorHelperException.java` | Create — abstract base, extends `RuntimeException` |
| `MetadataWriteException.java` | Create — concrete, carries `jobId`, `sdkResponse`, `cause` |

### Phase 2: Refactor `SftpConnectorHelper.java` and update unit tests (atomic)

1. Change all public method return types from `SftpOperationResult<T>` to `T`.
2. In `writeMetadataAndReturn()`: return `response` on success, throw `MetadataWriteException` on `DynamoDbException`, throw `MetadataWriteException` (with "unexpected duplicate" message) on `ConditionalCheckFailedException`.
3. Same for `writeMetadataWithBatchFields()`.
4. Update all unit tests in the same commit (see Phase 4 table below for details).

### Phase 3: Delete obsolete files

| File | Action |
|------|--------|
| `SftpOperationResult.java` | Delete |
| `SftpOperationResultTest.java` | Delete |

### Phase 4: Unit test changes (applied in Phase 2)

| File | Changes |
|------|---------|
| `SftpConnectorHelperTest.java` | Replace `assertInstanceOf(Success.class, ...)` with direct assertions on return value. Replace `assertInstanceOf(MetadataWriteFailed.class, ...)` with `assertThrows(MetadataWriteException.class, ...)`. Remove `MetadataAlreadyExists` test — replace with test that `ConditionalCheckFailedException` throws `MetadataWriteException`. |
| `SftpConnectorHelperRemoteMoveTest.java` | Same pattern |
| `SftpConnectorHelperRemoteDeleteTest.java` | Same pattern |
| `SftpConnectorHelperDirectoryListingTest.java` | Same pattern |

### Phase 5: Update integration tests

All 9 integration test files: remove `assertInstanceOf` + cast boilerplate, use return value directly.

| File | Changes |
|------|---------|
| `StartFileTransferIT.java` | `var response = helper.startFileTransfer(...)` directly |
| `StartDirectoryListingIT.java` | Same |
| `StartRemoteMoveIT.java` | Same |
| `StartRemoteDeleteIT.java` | Same |
| `MultiFileTransferIT.java` | Same |
| `BatchTimeoutIT.java` | Same |
| `IdempotencyIT.java` | Repurpose: verify that consecutive calls to the same operation produce distinct job IDs (regression guard for uniqueness assumption). |
| `LatencyBenchmarkIT.java` | Simplify `timeHelper` method |
| `IntegrationTestBase.java` | Remove `SftpOperationResult` import if present |

### Phase 6: Update documentation

| File | Section to update |
|------|-------------------|
| `README.md` | "First API Call" code example |
| `docs/GETTING_STARTED.md` | All scenario code examples |
| `docs/USER_GUIDE.md` | API reference section (result types → exception contract) |

### Phase 7: Version bump and changelog

This is a **breaking API change**. Bump the major version in `pom.xml` (e.g., `1.x.y` → `2.0.0`).

Add a `CHANGELOG.md` entry documenting:
- The breaking change (removal of `SftpOperationResult<T>`)
- Migration instructions (before/after pattern)
- New exception types introduced

## Files Affected (Summary)

| Category | Files | Count |
|----------|-------|-------|
| New | `SftpConnectorHelperException.java`, `MetadataWriteException.java` | 2 |
| Modified | `SftpConnectorHelper.java` | 1 |
| Deleted | `SftpOperationResult.java`, `SftpOperationResultTest.java` | 2 |
| Test updates | 4 unit test files + 9 integration test files | 13 |
| Doc updates | `README.md`, `docs/GETTING_STARTED.md`, `docs/USER_GUIDE.md`, `CHANGELOG.md` | 4 |
| Version | `pom.xml` (helpers/java) | 1 |
| **Total** | | **23** |

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| Breaking change for existing callers | Major version bump; clear migration path in changelog |
| `ConditionalCheckFailedException` hides a real bug | Throw `MetadataWriteException` with explicit "unexpected duplicate" message + WARN log; retain DynamoDB condition as safety net |
| Callers who relied on `MetadataWriteFailed` to continue silently | Document try/catch pattern in User Guide; `MetadataWriteException` carries the SDK response so callers can still proceed |

## Out of Scope

- Changes to the Lambda pipeline (event-writer, joiner) — unaffected.
- Changes to the CDK construct — unaffected.
- Changes to `DirectoryListingFilter` — already uses exceptions correctly.
- Changes to `FileTransferOptions` / `EventEmissionMode` — unaffected.
