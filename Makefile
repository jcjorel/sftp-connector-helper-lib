SHELL := /bin/bash

# Required environment variables for deployment
AWS_PROFILE ?=
AWS_REGION ?=

# Prerequisite checks
.PHONY: check-tools check-deploy-env

check-tools:
	@command -v pip >/dev/null 2>&1 || { echo "ERROR: 'pip' not found."; exit 1; }
	@command -v cdk >/dev/null 2>&1 || { echo "ERROR: 'cdk' not found. Install: npm install -g aws-cdk"; exit 1; }
	@command -v mvn >/dev/null 2>&1 || { echo "ERROR: 'mvn' not found. Install Maven 3.x+"; exit 1; }
	@command -v aws >/dev/null 2>&1 || { echo "ERROR: 'aws' CLI not found."; exit 1; }

check-deploy-env:
	@[ -n "$(AWS_PROFILE)" ] || { echo "ERROR: AWS_PROFILE is required. Usage: make deploy AWS_PROFILE=<profile> AWS_REGION=<region>"; exit 1; }
	@[ -n "$(AWS_REGION)" ] || { echo "ERROR: AWS_REGION is required. Usage: make deploy AWS_PROFILE=<profile> AWS_REGION=<region>"; exit 1; }
	@aws sts get-caller-identity --profile $(AWS_PROFILE) --region $(AWS_REGION) >/dev/null 2>&1 || { echo "ERROR: AWS credentials invalid for profile '$(AWS_PROFILE)'. Check your credentials."; exit 1; }

# Build targets
build-java: check-tools
	mvn compile -q -pl helpers/java

test-java: check-tools
	mvn test -pl helpers/java

build-lambdas: check-tools
	@set -e; for dir in lambdas/*/; do \
		[ "$${dir}" = "lambdas/shared/" ] && continue; \
		cd $$dir && \
		rm -rf dist/ && \
		pip install --target dist/ -r requirements.txt -q && \
		cp *.py dist/ && \
		cp ../shared/*.py dist/ && \
		cd ../..; \
	done

build: build-java build-lambdas

# Deploy: requires AWS_PROFILE and AWS_REGION
# Automatically reuses an existing DynamoDB table if the stack is not deployed
STACK_NAME := SftpConnectorHelperStack
TABLE_NAME := sftp-connector-helper

deploy: check-deploy-env build-lambdas
	@CONTEXT_ARG=""; \
	TABLE_IN_STACK="false"; \
	STACK_STATUS=$$(aws cloudformation describe-stacks --stack-name $(STACK_NAME) \
		--profile $(AWS_PROFILE) --region $(AWS_REGION) --query 'Stacks[0].StackStatus' --output text 2>/dev/null); \
	if [ -n "$$STACK_STATUS" ] && ! echo "$$STACK_STATUS" | grep -qE '(ROLLBACK_COMPLETE|CREATE_FAILED|DELETE_COMPLETE|REVIEW_IN_PROGRESS)'; then \
		TABLE_IN_STACK=$$(aws cloudformation list-stack-resources --stack-name $(STACK_NAME) \
			--profile $(AWS_PROFILE) --region $(AWS_REGION) \
			--query "StackResourceSummaries[?ResourceType=='AWS::DynamoDB::Table'] | length(@)" --output text 2>/dev/null); \
		if [ "$$TABLE_IN_STACK" = "0" ]; then TABLE_IN_STACK="false"; else TABLE_IN_STACK="true"; fi; \
	fi; \
	if [ -z "$$STACK_STATUS" ] || echo "$$STACK_STATUS" | grep -qE '(ROLLBACK_COMPLETE|CREATE_FAILED|DELETE_COMPLETE|REVIEW_IN_PROGRESS)' || [ "$$TABLE_IN_STACK" = "false" ]; then \
		TABLE_DESC=$$(aws dynamodb describe-table --table-name $(TABLE_NAME) \
			--profile $(AWS_PROFILE) --region $(AWS_REGION) --output json 2>/dev/null); \
		if [ -n "$$TABLE_DESC" ]; then \
			TABLE_ARN=$$(echo "$$TABLE_DESC" | python3 -c "import sys,json;print(json.load(sys.stdin)['Table']['TableArn'])"); \
			STREAM_ARN=$$(echo "$$TABLE_DESC" | python3 -c "import sys,json;print(json.load(sys.stdin)['Table'].get('LatestStreamArn',''))"); \
			if [ -n "$$TABLE_ARN" ] && [ "$$TABLE_ARN" != "None" ] && [ -n "$$STREAM_ARN" ]; then \
				echo "ℹ️  DynamoDB table '$(TABLE_NAME)' exists outside stack. Reusing existing table."; \
				CONTEXT_ARG="--context existing_table_arn=$$TABLE_ARN --context existing_table_stream_arn=$$STREAM_ARN"; \
			fi; \
		fi; \
	fi; \
	cd cdk && source .venv/bin/activate && \
		AWS_REGION=$(AWS_REGION) \
		CDK_DEFAULT_REGION=$(AWS_REGION) \
		CDK_DEFAULT_ACCOUNT=$$(aws sts get-caller-identity --profile $(AWS_PROFILE) --region $(AWS_REGION) --query Account --output text) \
		cdk deploy --profile $(AWS_PROFILE) --require-approval never $$CONTEXT_ARG

# Bootstrap CDK in target region (one-time setup)
bootstrap: check-deploy-env
	cd cdk && source .venv/bin/activate && \
		cdk bootstrap aws://$$(aws sts get-caller-identity --profile $(AWS_PROFILE) --region $(AWS_REGION) --query Account --output text)/$(AWS_REGION) \
		--profile $(AWS_PROFILE)

# Destroy stack
destroy: check-deploy-env
	cd cdk && source .venv/bin/activate && \
		AWS_REGION=$(AWS_REGION) \
		CDK_DEFAULT_REGION=$(AWS_REGION) \
		CDK_DEFAULT_ACCOUNT=$$(aws sts get-caller-identity --profile $(AWS_PROFILE) --region $(AWS_REGION) --query Account --output text) \
		cdk destroy --profile $(AWS_PROFILE) --force

test: test-java

# Integration tests: requires a deployed stack and SFTP Connector configuration.
#
# Required environment variables (no defaults — must be set explicitly):
#   CONNECTOR_ID   - Transfer Family SFTP Connector ID (e.g. c-0123456789abcdef0)
#   TEST_S3_BUCKET - S3 bucket accessible by the connector for test file staging
#   REMOTE_DIR     - Remote SFTP directory path used for test transfers
#
# Optional (auto-discovered from deployed stack if omitted):
#   TABLE_NAME     - DynamoDB table name (default: sftp-connector-helper)
#   EVENT_BUS_NAME - EventBridge bus name (default: sftp-connector-helper-bus)
#
# Example:
#   make test-integration AWS_PROFILE=dev AWS_REGION=eu-central-1 \
#     CONNECTOR_ID=c-0a1b2c3d4e5f67890 \
#     TEST_S3_BUCKET=my-sftp-test-bucket \
#     REMOTE_DIR=/upload/test

CONNECTOR_ID ?=
TEST_S3_BUCKET ?=
REMOTE_DIR ?=
TABLE_NAME ?= sftp-connector-helper
EVENT_BUS_NAME ?= sftp-connector-helper-bus

check-integration-env: check-deploy-env
	@[ -n "$(CONNECTOR_ID)" ] || { echo "ERROR: CONNECTOR_ID is required. Set to your Transfer Family SFTP Connector ID (e.g. c-0a1b2c3d4e5f67890)"; exit 1; }
	@[ -n "$(TEST_S3_BUCKET)" ] || { echo "ERROR: TEST_S3_BUCKET is required. Set to an S3 bucket accessible by the connector."; exit 1; }
	@[ -n "$(REMOTE_DIR)" ] || { echo "ERROR: REMOTE_DIR is required. Set to the remote SFTP directory path for test transfers."; exit 1; }

test-integration: check-integration-env
	CONNECTOR_ID=$(CONNECTOR_ID) \
	TABLE_NAME=$(TABLE_NAME) \
	EVENT_BUS_NAME=$(EVENT_BUS_NAME) \
	TEST_S3_BUCKET=$(TEST_S3_BUCKET) \
	REMOTE_DIR=$(REMOTE_DIR) \
	AWS_REGION=$(AWS_REGION) \
	AWS_PROFILE=$(AWS_PROFILE) \
	mvn verify -pl tests/integration/java -am

clean:
	mvn clean -q
