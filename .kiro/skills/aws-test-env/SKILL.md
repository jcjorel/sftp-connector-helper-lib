---
name: aws-test-env
description: AWS test environment parameters for running tests, deploying, or interacting with the SFTP Connector test infrastructure.
---

# AWS Test Environment

## Parameters

- **AWS_REGION**: `eu-central-1`
- **AWS_PROFILE**: `admin`
- **TEST_SFTP_CONNECTOR_ID**: `c-0123456789abcdef0`
- **TEST_SFTP_CONNECTOR_REMOTE_TEST_DIR**: `REDACTED_PATH/test_sftp_connector`
- **TEST_S3_BUCKET**: `sftp-connector-helper-test-123456789012`
- **TEST_S3_LISTING_PREFIX**: `listing-tests/`
- **TEST_S3_FILE_TRANSFER_PREFIX**: `file-transfer-tests/`

## SFTP Connector IAM Configuration

The connector's access role is `TransferSFTPConnectorRole` with inline policy `TransferSFTPConnectorPolicy`.

**Required S3 permissions** (on `sftp-connector-helper-test-123456789012`):
- `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`
- `s3:GetObjectVersion`, `s3:DeleteObjectVersion`
- `s3:GetBucketLocation`, `s3:ListBucket`

**Required Secrets Manager permission:**
- `secretsmanager:GetSecretValue` on `arn:aws:secretsmanager:eu-central-1:123456789012:secret:wigandale.dyndns.org-7geUQp`

**Note:** The `output-directory-path` for directory listings must point to a bucket/prefix that this role can write to. Without `s3:PutObject` on the output path, listings fail with "Access denied".

## AWS CLI Path Format Rules

**Critical:** The Transfer Family API has strict path format requirements:

- **`remote-directory-path`**: Must start with `/` and must **NOT** have a trailing slash.
  - ✅ `REDACTED_PATH/test_sftp_connector`
  - ❌ `REDACTED_PATH/test_sftp_connector/`
- **`output-directory-path`**: Must start with `/`, format is `/<bucket>/<prefix>` (no trailing slash).
  - ✅ `/sftp-connector-helper-test-123456789012/listing-tests`
  - ❌ `s3://sftp-connector-helper-test-123456789012/listing-tests/`
  - ❌ `/sftp-connector-helper-test-123456789012/listing-tests/`

## Common Operations

### List remote directory

```bash
aws transfer start-directory-listing \
  --connector-id c-0123456789abcdef0 \
  --remote-directory-path REDACTED_PATH/test_sftp_connector \
  --output-directory-path /sftp-connector-helper-test-123456789012/listing-tests \
  --profile admin --region eu-central-1
```

Returns `ListingId` and `OutputFileName`. Retrieve results after ~5s:

```bash
aws s3 cp s3://sftp-connector-helper-test-123456789012/listing-tests/<OutputFileName> - \
  --profile admin --region eu-central-1
```

### Send file to remote

```bash
aws transfer start-file-transfer \
  --connector-id c-0123456789abcdef0 \
  --send-file-paths /sftp-connector-helper-test-123456789012/file-transfer-tests/<filename> \
  --remote-directory-path REDACTED_PATH/test_sftp_connector \
  --profile admin --region eu-central-1
```

### Retrieve file from remote

```bash
aws transfer start-file-transfer \
  --connector-id c-0123456789abcdef0 \
  --retrieve-file-paths REDACTED_PATH/test_sftp_connector/<filename> \
  --local-directory-path /sftp-connector-helper-test-123456789012/file-transfer-tests \
  --profile admin --region eu-central-1
```
