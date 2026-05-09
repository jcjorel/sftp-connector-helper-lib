---
name: docs-separation
description: Prevents content duplication and misplacement across project documentation files. Use when reading or writing README.md, docs/ARCHITECTURE.md, or docs/USER_GUIDE.md. Do NOT use for general documentation writing.
---

# Documentation Separation Skill

The keywords MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, NOT RECOMMENDED, MAY, and OPTIONAL in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

A fact MUST appear in at most ONE file; cross-reference links replace repetition.

## Enforcement Procedure

1. MUST identify which ownership zone (see table below) the new content belongs to.
2. If content crosses a boundary, MUST move it to the correct file.
3. MUST use cross-references (`See [Architecture](docs/ARCHITECTURE.md)`) instead of duplicating.
4. MUST verify no content appears in more than one file after the edit.

## Cross-Reference Rules

| Rule | Constraint |
|------|-----------|
| README → other docs | MUST link to both in a "Documentation" section at the bottom |
| ARCHITECTURE.md → README | MUST NOT link back |
| USER_GUIDE.md → README | MUST NOT link back |
| ARCHITECTURE.md → USER_GUIDE.md | MAY reference for "how to consume" details |
| USER_GUIDE.md → ARCHITECTURE.md | MAY reference for "why it works this way" explanations |

## File Ownership Zones

| File | Role | MUST contain ONLY | MUST NOT contain |
|------|------|-------------------|------------------|
| `README.md` | Why and How to Start | Value proposition; feature bullet list (one-liner, no impl details); prerequisites + quick start (deploy commands, minimal code); performance summary table; project structure tree; build command table; links to other docs | CDK construct props; full API reference; system diagrams; DynamoDB schema / EventBridge format / IAM JSON; operational runbook / troubleshooting / cost estimates; idempotency contract; architectural decisions |
| `docs/ARCHITECTURE.md` | How It Works Inside | System diagram (ASCII/Mermaid); data flow (happy path, fan-out, orphan detection); CDK construct reference (props table, exposed properties, usage snippet); idempotency contract; DynamoDB table schema + GSI; supported event types + job ID extraction; CloudWatch metrics/alarms; architectural limitations | Value proposition / marketing; quick start / deploy commands; Java/Python API reference; operational runbook / troubleshooting; cost estimates; performance benchmarks |
| `docs/USER_GUIDE.md` | How to Use It Day-to-Day | Full Java API reference (builder, operations, result types, metadata format); IAM permissions for calling app; directory listing filter usage; enriched event format (JSON example); EventBridge rule examples (CDK + CLI); operational runbook (monitoring, DLQ, orphan response, log groups); troubleshooting; cost estimates | Value proposition / feature overview; quick start / deploy commands; system diagrams / internal data flow; CDK construct props; DynamoDB schema / idempotency internals; architectural decisions / limitations |

## Documentation Review Procedure

Use this procedure to audit all documentation files for ownership violations, missing cross-references, and content quality.

1. MUST spawn one subagent per documentation file for parallel, focused review.
2. Each subagent MUST read its assigned file and evaluate against the ownership zone table.
3. After all subagents complete, MUST synthesize findings into a single report.

### Subagent Dispatch

Spawn a blocking pipeline with three parallel review stages:

```
Stage 1 (parallel):
  - readme-review: Review README.md against its ownership zone
  - architecture-review: Review docs/ARCHITECTURE.md against its ownership zone
  - user-guide-review: Review docs/USER_GUIDE.md against its ownership zone

Stage 2 (depends on all Stage 1):
  - synthesis: Consolidate findings, detect cross-file duplications, produce final report
```

### Per-File Review Prompt Template

Each Stage 1 subagent MUST receive this prompt (with `{FILE}` and `{ROLE}` substituted):

```
Read {FILE} in full. Evaluate against these criteria:

1. OWNERSHIP VIOLATIONS: List any content that belongs in a different file per these rules:
   - {FILE} role: {ROLE}
   - MUST contain ONLY: {MUST_CONTAIN}
   - MUST NOT contain: {MUST_NOT_CONTAIN}

2. CROSS-REFERENCE COMPLIANCE: Check links follow the cross-reference rules:
   - README → other docs: MUST link in "Documentation" section
   - ARCHITECTURE/USER_GUIDE → README: MUST NOT link back
   - ARCHITECTURE ↔ USER_GUIDE: MAY cross-reference

3. CONTENT QUALITY: Flag stale information, broken links, inconsistencies with source code.

4. DUPLICATION CANDIDATES: Extract key phrases/facts that might also appear in other docs (to be checked in synthesis stage).

Output a structured report with sections: VIOLATIONS, CROSS-REFS, QUALITY, DUPLICATION_CANDIDATES.
```

### Synthesis Prompt Template

The Stage 2 subagent MUST receive:

```
Given the three file review reports below, produce a consolidated documentation review:

1. CROSS-FILE DUPLICATIONS: Compare duplication candidates across all three reports. Flag any fact appearing in more than one file.
2. OWNERSHIP MOVES: For each violation, specify: source file, content excerpt, destination file.
3. MISSING CROSS-REFERENCES: Identify places where a link SHOULD exist but doesn't.
4. PRIORITY RANKING: Order all findings by severity (ownership violation > duplication > missing cross-ref > quality).

Output format: Markdown table with columns: Priority, File, Issue, Recommended Action.
```

### Verification

After review completes:
- If violations are found, MUST present the consolidated report to the user before making changes.
- MUST NOT auto-fix without user confirmation.
