# SFTP Connector Helper — CDK Construct

AWS CDK construct that deploys the SFTP Connector Helper framework: DynamoDB correlation table, EventBridge event capture, Event Writer Lambda, Joiner pipeline, and CloudWatch alarms.

## TTL Configuration

The `ttl_duration` prop (default: 1 day) controls the TTL set on DynamoDB records via the `TTL_SECONDS` Lambda environment variable.

**Staggered TTL:** The master `FILE_TRANSFER` record uses `TTL + 1 hour` to ensure it outlives per-file records. This staggered logic is implemented in the **Java helper library at runtime** — the CDK construct only passes the base TTL value. The Java library adds 3600 seconds to the master record's TTL independently.

## Singleton Enforcement

This construct uses fixed resource names to prevent duplicate deployments in the same account/region:

| Resource | Fixed Name |
|----------|-----------|
| Event Writer SQS queue | `sftp-connector-helper-event-writer` |
| Joiner SQS FIFO queue | `sftp-connector-helper-joiner.fifo` |
| Joiner FIFO DLQ | `sftp-connector-helper-joiner-dlq.fifo` |
| Pipe DLQ | `sftp-connector-helper-pipe-dlq` |
| EventBridge rule | `sftp-connector-helper-event-capture` |

A second `cdk deploy` with a different stack will fail with CloudFormation name collisions.

## Deployment

```bash
make deploy  # builds lambdas then runs cdk deploy
```
