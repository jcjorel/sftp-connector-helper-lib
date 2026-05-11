# Changelog

## [2.0.0] - 2026-05-11

### Breaking Changes

- **Removed `SftpOperationResult<T>` sealed interface** — all helper methods now return the SDK response directly (`StartFileTransferResponse`, `StartDirectoryListingResponse`, `StartRemoteMoveResponse`, `StartRemoteDeleteResponse`).
- **Removed `MetadataAlreadyExists` variant** — investigation confirmed this code path was unreachable. The DynamoDB condition expression is retained as a safety net; if it fires, a `MetadataWriteException` is thrown with an "Unexpected duplicate metadata" message.

### Added

- `SftpConnectorHelperException` — abstract base class for all library-specific exceptions (extends `RuntimeException`).
- `MetadataWriteException` — thrown when DynamoDB metadata write fails after a successful SDK call. Carries `getJobId()`, `getSdkResponse()`, and `getCause()`.

### Changed

- **Integration test infrastructure** — initialize test resources once per JVM for faster test execution

### Documentation

- Added Maven Central publish skill

### Chores

- Add CHANGELOG gate to publish procedure
- Ignore Maven flatten plugin artifacts

### Migration Guide

**Before (1.x)**:
```java
SftpOperationResult<StartFileTransferResponse> result = helper.startFileTransfer(request, metadata);
if (result instanceof SftpOperationResult.Success<StartFileTransferResponse> s) {
    System.out.println("Transfer: " + s.response().transferId());
} else if (result instanceof SftpOperationResult.MetadataWriteFailed<StartFileTransferResponse> f) {
    log.warn("Metadata lost for {}", f.jobId(), f.cause());
}
```

**After (2.0)**:
```java
try {
    StartFileTransferResponse response = helper.startFileTransfer(request, metadata);
    System.out.println("Transfer: " + response.transferId());
} catch (MetadataWriteException e) {
    log.warn("Metadata lost for {}", e.getJobId(), e);
}
```

Callers who do not need graceful degradation can simply remove the try/catch — `MetadataWriteException` is unchecked and will propagate naturally.

## [1.0.1] - 2026-05-11

### Added

- **Batch timeout completion** — automatic timeout event when multi-file transfers don't complete within a configurable duration (default: 1 hour)
- Batch timeout documentation and design specs

### Changed

- Improved JavaDoc across the helper library

### Chores

- Ignore Maven versionsBackup files
- Fix skill name and enhance doc review skill

## [1.0.0] - 2026-05-11

### Added

- **Core helper library** — `SftpConnectorHelper` with metadata correlation for all SFTP Connector operations:
  - `startFileTransfer` (single and multi-file with fan-out)
  - `startDirectoryListing` with `DirectoryListingFilter` for regex-based filtering
  - `startRemoteMove`
  - `startRemoteDelete`
- **Whole-transfer batch completion events** — single "all done" signal for multi-file fan-out workflows
- **CDK construct** — turnkey deployment of the entire enrichment pipeline:
  - Core infrastructure (DynamoDB table, EventBridge bus, SNS topic)
  - Event Writer Lambda (captures Transfer Family events into DynamoDB)
  - Joiner Lambda (joins metadata + events, publishes enriched events)
  - EventBridge Pipe for stream processing
- **Orphan detection** — automatic alerting via SNS when Transfer Family events arrive without matching metadata
- **Integration tests** — end-to-end tests for full pipeline and orphan detection
- **Documentation** — README, Getting Started guide, Architecture docs, User Guide, CDK construct README
- **Metadata validation** — input validation for metadata payloads
- **Idempotent design** — safe-to-retry metadata correlation

### Infrastructure

- EventBridge bus logging for observability
- Direct Lambda invocations (replaced SQS queues) for reduced latency
- Shared logging utilities with timestamp extraction
- Explicit Lambda log groups
- Support for existing DynamoDB table stream ARN for Pipe reuse
- Auto-reuse orphaned DynamoDB table on deploy when stack is absent
- Explicit IAM policy statements (replaced `table.grant()`)
- Require explicit `AWS_PROFILE` and `AWS_REGION` for deployment safety