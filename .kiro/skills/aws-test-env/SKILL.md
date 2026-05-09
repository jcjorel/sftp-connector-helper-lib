---
name: aws-test-env
description: AWS test environment parameters for running tests, deploying, or interacting with the SFTP Connector test infrastructure.
---

# AWS Test Environment

## Parameters

- **AWS_REGION**: `eu-central-1`
- **AWS_PROFILE**: `sftp-connector-helper-test-env`
- **TEST_SFTP_CONNECTOR_ID**: `c-dfcf9a722103469ca`
- **TEST_SFTP_CONNECTOR_REMOTE_TEST_DIR**: `/tmp/test_sftp_connector`
- **TEST_S3_BUCKET**: `sftp-connector-helper-test-a1b2c3d4e5f6`
- **TEST_S3_LISTING_PREFIX**: `listing-tests/`
- **TEST_S3_FILE_TRANSFER_PREFIX**: `file-transfer-tests/`

## SFTP Connector IAM Configuration

The connector's access role is `TransferSFTPConnectorRole` with inline policy `TransferSFTPConnectorPolicy`.

**Required S3 permissions** (on `sftp-connector-helper-test-a1b2c3d4e5f6`):
- `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`
- `s3:GetObjectVersion`, `s3:DeleteObjectVersion`
- `s3:GetBucketLocation`, `s3:ListBucket`

**Required Secrets Manager permission:**
- `secretsmanager:GetSecretValue` on the ARN of the AWS SecretManager secret used by the test AWS TransferFamily SFTP Connector.

**Note:** The `output-directory-path` for directory listings must point to a bucket/prefix that this role can write to. Without `s3:PutObject` on the output path, listings fail with "Access denied".

Note: When verifying the AWS test environment readiness, control that the connector's access role has the above permissions, and that the specified S3 bucket and prefix exist and are accessible to this role.

## AWS CLI Path Format Rules

**Critical:** The Transfer Family API has strict path format requirements:

- **`remote-directory-path`**: Must start with `/` and must **NOT** have a trailing slash.
  - ✅ `/tmp/test_sftp_connector`
  - ❌ `/tmp/test_sftp_connector/`
- **`output-directory-path`**: Must start with `/`, format is `/<bucket>/<prefix>` (no trailing slash).
  - ✅ `/sftp-connector-helper-test-a1b2c3d4e5f6/listing-tests`
  - ❌ `s3://sftp-connector-helper-test-a1b2c3d4e5f6/listing-tests/`
  - ❌ `/sftp-connector-helper-test-a1b2c3d4e5f6/listing-tests/`

## Common Operations

### List remote directory

```bash
aws transfer start-directory-listing \
  --connector-id c-dfcf9a722103469ca \
  --remote-directory-path /tmp/test_sftp_connector \
  --output-directory-path /sftp-connector-helper-test-a1b2c3d4e5f6/listing-tests \
  --profile sftp-connector-helper-test-env --region eu-central-1
```

Returns `ListingId` and `OutputFileName`. Retrieve results after ~5s:

```bash
aws s3 cp s3://sftp-connector-helper-test-a1b2c3d4e5f6/listing-tests/<OutputFileName> - \
  --profile sftp-connector-helper-test-env --region eu-central-1
```

### Send file to remote

```bash
aws transfer start-file-transfer \
  --connector-id c-dfcf9a722103469ca \
  --send-file-paths /sftp-connector-helper-test-a1b2c3d4e5f6/file-transfer-tests/<filename> \
  --remote-directory-path /tmp/test_sftp_connector \
  --profile sftp-connector-helper-test-env --region eu-central-1
```

### Retrieve file from remote

```bash
aws transfer start-file-transfer \
  --connector-id c-dfcf9a722103469ca \
  --retrieve-file-paths /tmp/test_sftp_connector/<filename> \
  --local-directory-path /sftp-connector-helper-test-a1b2c3d4e5f6/file-transfer-tests \
  --profile sftp-connector-helper-test-env --region eu-central-1
```
