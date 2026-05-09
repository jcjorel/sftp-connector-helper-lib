---
name: docs-separation
description: Enforces strict content boundaries between README.md, docs/ARCHITECTURE.md, and docs/USER_GUIDE.md. Use when reading or writing any of these three documentation files.
---

# Documentation Separation Skill

Enforces strict content boundaries between the three project documentation files. Apply these rules whenever reading or writing `README.md`, `docs/ARCHITECTURE.md`, or `docs/USER_GUIDE.md`.

## File Ownership Rules

### README.md — "Why and How to Start"
MUST contain ONLY:
- Value proposition (what the framework does, why it exists)
- Feature bullet list (one-liner per feature, no implementation details)
- Prerequisites and quick start (deploy commands, minimal code example)
- Performance summary (table with overhead numbers, no methodology details)
- Project structure tree (directory layout, one-line descriptions)
- Build command table
- Links to the other two docs

MUST NOT contain:
- CDK construct props/configuration reference
- Full API reference or method signatures beyond the quick-start snippet
- System diagrams or data flow explanations
- DynamoDB schema, EventBridge event format, or IAM policy JSON
- Operational runbook, troubleshooting, or cost estimates
- Idempotency contract details or architectural decisions

### docs/ARCHITECTURE.md — "How It Works Inside"
MUST contain ONLY:
- System diagram (ASCII or Mermaid)
- Data flow descriptions (happy path, fan-out, orphan detection)
- CDK construct reference (props table, exposed properties, usage snippet)
- Idempotency contract (per-component write semantics)
- DynamoDB table schema and GSI design
- Supported event types and job ID extraction logic
- CloudWatch metrics and alarms reference
- Architectural limitations

MUST NOT contain:
- Value proposition or marketing language
- Quick start or deployment commands
- Java/Python API reference or code examples for consumers
- Operational runbook or troubleshooting steps
- Cost estimates
- Performance benchmarks

### docs/USER_GUIDE.md — "How to Use It Day-to-Day"
MUST contain ONLY:
- Full Java API reference (builder, all operations, result types, metadata format)
- IAM permissions required by the calling application
- Directory listing filter usage
- Enriched event format (JSON example)
- EventBridge rule examples (CDK + CLI)
- Operational runbook (monitoring, DLQ inspection, orphan response, log groups)
- Troubleshooting scenarios
- Cost estimates table

MUST NOT contain:
- Value proposition or feature overview
- Quick start or deployment commands
- System diagrams or internal data flow
- CDK construct props reference
- DynamoDB schema or idempotency contract internals
- Architectural decisions or limitations

## Enforcement Procedure

When modifying any of the three files:
1. Identify which ownership zone the new content belongs to
2. If content crosses a boundary, move it to the correct file
3. Use cross-references (`See [Architecture](docs/ARCHITECTURE.md)`) instead of duplicating
4. A fact may appear in at most ONE file; links replace repetition

## Cross-Reference Convention

- README links to both docs at the bottom in a "Documentation" section
- ARCHITECTURE.md and USER_GUIDE.md do NOT link back to README
- ARCHITECTURE.md may reference USER_GUIDE.md for "how to consume" details
- USER_GUIDE.md may reference ARCHITECTURE.md for "why it works this way" explanations
