# SFTP Connector Helper Library

A framework that adds **business metadata correlation** to AWS Transfer Family SFTP Connector operations. It bridges the gap between your application's business context and the asynchronous events produced by SFTP Connector, delivering enriched events that carry your custom metadata alongside Transfer Family's native event data.

## Key Features

- **Metadata correlation** — Attach arbitrary JSON metadata to any SFTP Connector operation (file send, directory listing, move, delete)
- **Enriched events** — Consume events on a dedicated EventBridge bus with your metadata already joined (captures both file send and file retrieve events)
- **Idempotent by design** — Safe-to-retry metadata correlation
- **Orphan detection** — Automatic alerting via SNS when Transfer Family events arrive without matching metadata (accessible as `construct.orphan_topic`)
- **Turnkey deployment** — Single CDK construct deploys the entire pipeline
- **Directory listing filter** — Client-side utility for regex-based filtering of listing results from S3 output referenced in enriched events

## Quick Start

### Prerequisites

- Node.js 18+ and AWS CDK v2 (`npm install -g aws-cdk`)
- Python 3.12+ with `uv` package manager
- Java 21+ with Maven
- AWS CLI v2 configured with a named profile

### Deploy

```bash
make bootstrap AWS_PROFILE=my-profile AWS_REGION=eu-central-1  # one-time
make deploy AWS_PROFILE=my-profile AWS_REGION=eu-central-1
```

### First API Call (Java)

```java
import io.github.jcjorel.sftpconnectorhelper.*;

try (SftpConnectorHelper helper = SftpConnectorHelper.builder().build()) {

    StartFileTransferRequest request = StartFileTransferRequest.builder()
        .connectorId("c-1234567890abcdef0")
        .sendFilePaths("/outbound/invoice-001.csv")
        .build();

    String metadata = "{\"orderId\":\"ORD-001\",\"customer\":\"ACME\"}";

    SftpOperationResult<StartFileTransferResponse> result = helper.startFileTransfer(request, metadata);

    switch (result) {
        case SftpOperationResult.Success<StartFileTransferResponse> s ->
            System.out.println("Transfer started: " + s.response().transferId());
        case SftpOperationResult.MetadataWriteFailed<StartFileTransferResponse> f ->
            System.err.println("Transfer OK but metadata write failed: " + f.cause().getMessage());
        case SftpOperationResult.MetadataAlreadyExists<StartFileTransferResponse> e ->
            System.err.println("Duplicate metadata for job: " + e.jobId());
    }
}
```

### Consume Enriched Events (Lambda)

```python
def lambda_handler(event, context):
    detail = event["detail"]
    metadata = detail.get("_helper_metadata", {})  # injected by the joiner with your original metadata
    order_id = metadata.get("orderId")
    status = detail.get("status-code")
    print(f"Order {order_id}: status={status}")
```

## Performance

The helper adds a single metadata write per operation (~5–10 ms typical). End-to-end latency overhead is dominated by SFTP Connector variance, not the metadata write:

| Operation | Direct (min / **mean** / max) | Helper (min / **mean** / max) | Overhead (min / **mean** / max) |
|-----------|-------------------------------|-------------------------------|-------------------------------|
| StartFileTransfer | 1263 / **1585** / 2036 ms | 1432 / **2024** / 3129 ms | −339 / **+438** / +1341 ms |
| StartDirectoryListing | 651 / **1381** / 2338 ms | 1097 / **1760** / 2192 ms | −830 / **+379** / +1390 ms |
| StartRemoteMove | 481 / **1549** / 2453 ms | 867 / **1227** / 1374 ms | −1410 / **−322** / +866 ms |
| StartRemoteDelete | 716 / **1178** / 2232 ms | 904 / **1492** / 2133 ms | −605 / **+314** / +1136 ms |
| MultiFileSend (10 files) | 4256 / **6060** / 8949 ms | 5184 / **5531** / 5857 ms | −3220 / **−528** / +1601 ms |
| MultiFileRetrieve (10 files) | 2936 / **4240** / 5206 ms | 3863 / **4701** / 5341 ms | +91 / **+461** / +1366 ms |

Run the benchmark: `make test-integration AWS_PROFILE=<profile> AWS_REGION=<region>`

> Integration tests require environment-specific variables (`CONNECTOR_ID`, `TEST_S3_BUCKET`, `REMOTE_DIR`) — edit the Makefile `test-integration` target for your environment.

## Project Structure

```
├── helpers/java/          # Java helper library (Maven)
├── lambdas/
│   ├── event-writer/      # Captures Transfer Family events into DynamoDB
│   ├── joiner/            # Joins metadata + events, publishes enriched events
│   └── shared/            # Common utilities (structured logging)
├── cdk/                   # CDK construct and deployment
├── tests/integration/     # End-to-end integration tests (Maven)
├── docs/                  # Architecture and User Guide
└── Makefile               # Build orchestration
```

## Build Commands

| Command | Description |
|---------|-------------|
| `make build` | Build Java library + Lambda packages |
| `make deploy AWS_PROFILE=x AWS_REGION=y` | Build + deploy to specified region |
| `make destroy AWS_PROFILE=x AWS_REGION=y` | Tear down the deployed stack |
| `make test` | Run Java unit tests |
| `make test-integration` | Run integration tests (requires deployed stack) |

## Documentation

- **[Architecture](docs/ARCHITECTURE.md)** — System design, data flow, CDK construct reference, idempotency contract
- **[User Guide](docs/USER_GUIDE.md)** — Full API reference, event format, operational runbook, troubleshooting, cost estimates
