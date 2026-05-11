# SFTP Connector Helper Library

A framework that adds **business metadata correlation** to AWS Transfer Family SFTP Connector operations. It bridges the gap between your application's business context and the asynchronous events produced by SFTP Connector, delivering enriched events that carry your custom metadata alongside Transfer Family's native event data.

## Problem & Motivation

AWS Transfer Family SFTP Connector operations are **asynchronous** — you call `StartFileTransfer` and later receive an EventBridge event when the transfer completes. But that event only contains Transfer Family identifiers (`transfer-id`, `connector-id`, `status-code`). It carries **no business context**: which order triggered the transfer, which customer it belongs to, or what to do next.

This forces you to build correlation infrastructure yourself:
- Store a mapping between transfer IDs and your business data
- Handle race conditions (events can arrive before your mapping is written)
- Deal with multi-file fan-out (one API call → N events, no batch completion signal)
- Detect orphans when events or metadata go missing

This library solves all of that with a single CDK construct and a thin Java wrapper. You pass your metadata alongside the SDK call; the framework delivers enriched events with your metadata already joined.

**Use this library when** you need to react to SFTP Connector completion events with business context (order IDs, customer references, workflow state). **Skip it** if you only need fire-and-forget file transfers with no downstream event processing.

## Key Features

- **Metadata correlation** — Attach arbitrary JSON metadata to any SFTP Connector operation (file send, directory listing, move, delete)
- **Enriched events** — Consume events on a dedicated EventBridge bus with your metadata already joined (captures both file send and file retrieve events)
- **Idempotent by design** — Safe-to-retry metadata correlation
- **Orphan detection** — Automatic alerting via SNS when Transfer Family events arrive without matching metadata
- **Batch timeout** — Automatic timeout event when multi-file transfers don't complete within a configurable duration (default: 1 hour)
- **Turnkey deployment** — Single CDK construct deploys the entire pipeline
- **Directory listing filter** — Client-side utility for regex-based filtering of listing results from S3 output referenced in enriched events

## Quick Start

### Prerequisites

- Node.js 18+ and AWS CDK v2 (`npm install -g aws-cdk`)
- Python 3.12+ with `uv` package manager
- Java 21+ with Maven
- AWS CLI v2 configured with a named profile
- IAM permissions for your application (see [User Guide — IAM Permissions](docs/USER_GUIDE.md#iam-permissions-required-by-caller))

### Deploy

```bash
make bootstrap AWS_PROFILE=my-profile AWS_REGION=eu-central-1  # one-time
make deploy AWS_PROFILE=my-profile AWS_REGION=eu-central-1
```

### First API Call (Java)

```java
import io.github.jcjorel.sftpconnectorhelper.*;
import software.amazon.awssdk.services.transfer.model.*;

try (SftpConnectorHelper helper = SftpConnectorHelper.builder().build()) {

    StartFileTransferRequest request = StartFileTransferRequest.builder()
        .connectorId("c-1234567890abcdef0")
        .sendFilePaths("/outbound/invoice-001.csv")
        .build();

    StartFileTransferResponse response =
        helper.startFileTransfer(request, "{\"orderId\":\"ORD-001\",\"customer\":\"ACME\"}");

    System.out.println("Transfer started: " + response.transferId());
    // MetadataWriteException propagates if metadata write fails
}
```

See [Getting Started](docs/GETTING_STARTED.md) for complete error handling and all operation types.

### Consume Enriched Events

Your consumer receives the original Transfer Family event enriched with your metadata in `_helper_metadata`. See [Getting Started — Consume the Enriched Event](docs/GETTING_STARTED.md#scenario-2-consume-the-enriched-event) for event format and a complete consumer example.

## Performance

The helper adds a single metadata write per operation (~5–10 ms typical). End-to-end latency overhead is dominated by SFTP Connector variance, not the metadata write:

| Operation | Description | Direct (min / **mean** / max) | Helper (min / **mean** / max) | Overhead (min / **mean** / max) |
|-----------|-------------|-------------------------------|-------------------------------|-------------------------------|
| SingleFileSend | Send one file to the remote SFTP server | 518 / **1374** / 2946 ms | 1482 / **2209** / 3035 ms | −106 / **+835** / +1847 ms |
| SingleFileRetrieve | Retrieve one file from the remote SFTP server | 507 / **1236** / 1523 ms | 1675 / **2316** / 3169 ms | +194 / **+1079** / +2662 ms |
| StartDirectoryListing | List remote directory contents | 648 / **1847** / 5056 ms | 1182 / **1838** / 2165 ms | −2891 / **−9** / +1365 ms |
| StartRemoteMove | Move/rename a file on the remote server | 512 / **1020** / 2246 ms | 827 / **1487** / 2813 ms | +211 / **+466** / +894 ms |
| StartRemoteDelete | Delete a file on the remote server | 439 / **1083** / 2287 ms | 764 / **1278** / 1903 ms | −384 / **+195** / +771 ms |
| MultiFileSend (10 files) | Send 10 files, one enriched event per file | 4027 / **5598** / 8899 ms | 5246 / **5560** / 5871 ms | −3108 / **−38** / +1219 ms |
| MultiFileSend-WholeOnly (10 files) | Send 10 files, single batch-completion event | 4027 / **5598** / 8899 ms | 5514 / **6012** / 6676 ms | −2223 / **+414** / +2298 ms |
| MultiFileSend-Ind+Whole (10 files) | Send 10 files, per-file + batch-completion events | 4027 / **5598** / 8899 ms | 5688 / **5913** / 6282 ms | −3090 / **+314** / +1746 ms |
| MultiFileRetrieve (10 files) | Retrieve 10 files, one enriched event per file | 3817 / **4897** / 5357 ms | 4051 / **5824** / 7988 ms | +213 / **+926** / +2631 ms |
| MultiFileRetrieve-WholeOnly (10 files) | Retrieve 10 files, single batch-completion event | 3817 / **4897** / 5357 ms | 4301 / **5093** / 6135 ms | −583 / **+195** / +941 ms |
| MultiFileRetrieve-Ind+Whole (10 files) | Retrieve 10 files, per-file + batch-completion events | 3817 / **4897** / 5357 ms | 4475 / **5213** / 6071 ms | −194 / **+315** / +834 ms |

**Key takeaways** (5 iterations per operation):

- **Overhead is noise-dominated** — negative overhead values in several runs confirm that SFTP Connector variance far exceeds the helper's added latency.
- **Directory listing and multi-file sends show near-zero mean overhead** (−9 ms and −38 ms respectively), meaning the metadata write is fully amortized.
- **Single-file operations** add ~835–1079 ms mean overhead, largely due to the enriched event pipeline rather than the metadata write itself.
- **Batch-completion mode** (`WholeOnly`, `Ind+Whole`) adds minimal cost over per-file mode while providing a single "all done" signal for fan-out workflows.

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
| `make bootstrap AWS_PROFILE=x AWS_REGION=y` | One-time CDK bootstrap in target region |
| `make test` | Run Java unit tests |
| `make test-integration` | Run integration tests (requires deployed stack) |
| `make clean` | Remove Java build artifacts |

## Documentation

- **[Getting Started](docs/GETTING_STARTED.md)** — Hands-on tutorial with progressive scenarios
- **[Architecture](docs/ARCHITECTURE.md)** — System design, data flow, CDK construct reference, idempotency contract
- **[User Guide](docs/USER_GUIDE.md)** — Full API reference, event format, operational runbook, troubleshooting, cost estimates
