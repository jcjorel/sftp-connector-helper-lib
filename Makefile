SHELL := /bin/bash

# Required environment variables for deployment
AWS_PROFILE ?=
AWS_REGION ?=

# Prerequisite checks
.PHONY: check-tools check-deploy-env

check-tools:
	@command -v uv >/dev/null 2>&1 || { echo "ERROR: 'uv' not found. Install: https://docs.astral.sh/uv/getting-started/installation/"; exit 1; }
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
	cd helpers/java && mvn compile -q

test-java: check-tools
	cd helpers/java && mvn test

build-lambdas: check-tools
	@set -e; for dir in lambdas/*/; do \
		cd $$dir && \
		rm -rf dist/ && \
		uv export --format requirements-txt --no-dev --no-emit-project -o requirements.txt && \
		pip install --target dist/ -r requirements.txt -q && \
		cp *.py dist/ && \
		cd ../..; \
	done

build: build-java build-lambdas

# Deploy: requires AWS_PROFILE and AWS_REGION
# Automatically reuses an existing DynamoDB table if the stack is not deployed
STACK_NAME := SftpConnectorHelperStack
TABLE_NAME := sftp-connector-helper

deploy: check-deploy-env build-lambdas
	@CONTEXT_ARG=""; \
	STACK_STATUS=$$(aws cloudformation describe-stacks --stack-name $(STACK_NAME) \
		--profile $(AWS_PROFILE) --region $(AWS_REGION) --query 'Stacks[0].StackStatus' --output text 2>/dev/null); \
	if [ -z "$$STACK_STATUS" ] || echo "$$STACK_STATUS" | grep -qE '(ROLLBACK_COMPLETE|CREATE_FAILED|DELETE_COMPLETE|REVIEW_IN_PROGRESS)'; then \
		TABLE_ARN=$$(aws dynamodb describe-table --table-name $(TABLE_NAME) \
			--profile $(AWS_PROFILE) --region $(AWS_REGION) --query 'Table.TableArn' --output text 2>/dev/null); \
		if [ -n "$$TABLE_ARN" ] && [ "$$TABLE_ARN" != "None" ]; then \
			echo "ℹ️  Stack not found but DynamoDB table '$(TABLE_NAME)' exists. Reusing existing table."; \
			CONTEXT_ARG="--context existing_table_arn=$$TABLE_ARN"; \
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

test-integration:
	cd tests/integration && uv run pytest -v

clean:
	cd helpers/java && mvn clean -q
