---
name: aws-test-env
description: AWS test environment parameters for running tests, deploying, or interacting with the SFTP Connector test infrastructure. Use when running integration tests, deploying the stack, or making AWS CLI calls against the test environment. Do NOT use for production operations or non-test AWS accounts.
---

The keywords MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, NOT RECOMMENDED, MAY, and OPTIONAL in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

# AWS Test Environment

## Parameters

- **AWS_REGION**: `eu-central-1`
- **AWS_PROFILE**: `sftp-connector-helper-test-env`
- **TEST_SFTP_CONNECTOR_ID**: `c-dfcf9a722103469ca`
- **TEST_SFTP_CONNECTOR_REMOTE_TEST_DIR**: `/tmp/test_sftp_connector`
- **TEST_S3_BUCKET**: `sftp-connector-helper-test-a1b2c3d4e5f6`
- **TEST_S3_LISTING_PREFIX**: `listing-tests/`
- **TEST_S3_FILE_TRANSFER_PREFIX**: `file-transfer-tests/`

## Path Format Constraints

- `remote-directory-path` MUST start with `/` and MUST NOT have a trailing slash.
  - ✅ `/tmp/test_sftp_connector`
  - ❌ `/tmp/test_sftp_connector/`
- `output-directory-path` MUST start with `/`, format `/<bucket>/<prefix>` — MUST NOT use `s3://` scheme or trailing slash.
  - ✅ `/sftp-connector-helper-test-a1b2c3d4e5f6/listing-tests`
  - ❌ `s3://sftp-connector-helper-test-a1b2c3d4e5f6/listing-tests/`
  - ❌ `/sftp-connector-helper-test-a1b2c3d4e5f6/listing-tests/`

## SFTP Connector IAM Configuration

The connector's access role is `TransferSFTPConnectorRole` with inline policy `TransferSFTPConnectorPolicy`.

**Required S3 permissions** (on `sftp-connector-helper-test-a1b2c3d4e5f6`):
- `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`
- `s3:GetObjectVersion`, `s3:DeleteObjectVersion`
- `s3:GetBucketLocation`, `s3:ListBucket`

**Required Secrets Manager permission:**
- `secretsmanager:GetSecretValue` on the ARN of the secret used by the test SFTP Connector.

The connector role MUST have `s3:PutObject` on the output path for directory listings. Without it, listings fail with "Access denied".

## Verification

- If verifying environment readiness, MUST confirm:
  1. The connector's access role has all permissions listed above.
  2. The S3 bucket and prefixes exist and are accessible to the role.
  3. A test `start-directory-listing` completes without "Access denied".
- If a listing result S3 object does not appear after 5 seconds, SHOULD retry after 10 seconds. If it still fails, verify the connector role has `s3:PutObject` on the output path.

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
