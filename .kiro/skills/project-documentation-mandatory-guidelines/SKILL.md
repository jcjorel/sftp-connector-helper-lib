---
name: project-documentation-mandatory-guidelines
description: Prevents content duplication and misplacement across project documentation files. Use when reading, writing, improving or reviewing README.md, docs/ARCHITECTURE.md, docs/USER_GUIDE.md, or docs/GETTING_STARTED.md. Do NOT use for general documentation writing.
---

# Documentation Separation Skill

The keywords MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, NOT RECOMMENDED, MAY, and OPTIONAL in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

A fact MUST appear in at most ONE file; cross-reference links replace repetition.

## Procedure Selection

- If the user asks to REVIEW, AUDIT, or CHECK documentation → MUST follow the Documentation Review Procedure.
- If the user asks to WRITE, EDIT, IMPROVE, or UPDATE a specific file → MUST follow the Enforcement Procedure.

## Enforcement Procedure

1. MUST identify which ownership zone (see table below) the new content belongs to.
2. If content crosses a boundary, MUST move it to the correct file.
3. MUST use cross-references (`See [Architecture](docs/ARCHITECTURE.md)`) instead of duplicating.
4. MUST verify no content appears in more than one file after the edit.

## Cross-Reference Rules

| Rule | Constraint |
|------|-----------|
| README → other docs | MUST link to all docs in a "Documentation" section at the bottom |
| GETTING_STARTED.md → README | MAY reference for deploy prerequisites |
| GETTING_STARTED.md → USER_GUIDE.md | MUST link for full API details instead of duplicating |
| GETTING_STARTED.md → ARCHITECTURE.md | MAY link for "how it works under the hood" explanations |
| ARCHITECTURE.md → README | MUST NOT link back |
| ARCHITECTURE.md → USER_GUIDE.md | MAY reference for "how to consume" details |
| ARCHITECTURE.md → GETTING_STARTED.md | MAY reference for "see tutorial" pointers |
| USER_GUIDE.md → README | MUST NOT link back |
| USER_GUIDE.md → ARCHITECTURE.md | MAY reference for "why it works this way" explanations |
| USER_GUIDE.md → GETTING_STARTED.md | MAY reference for "see tutorial" pointers |

## File Ownership Zones

| File | Role | MUST contain ONLY | MUST NOT contain |
|------|------|-------------------|------------------|
| `README.md` | Why and How to Start | Value proposition; feature bullet list (one-liner, no impl details); prerequisites + quick start (deploy commands, minimal code); performance summary table; project structure tree; build command table; links to other docs | CDK construct props; full API reference; system diagrams; DynamoDB schema / EventBridge format / IAM JSON; operational runbook / troubleshooting / cost estimates; idempotency contract; architectural decisions; step-by-step tutorials |
| `docs/GETTING_STARTED.md` | Learn by Doing | Progressive scenarios (single-file send, multi-file send, directory listing + filter, remote move/delete, consuming enriched events, orphan handling); each scenario with goal statement, complete runnable code, expected output, and "what just happened" explanation; prerequisite checklist referencing README deploy steps | Value proposition / feature overview; CDK construct props; DynamoDB schema / internals; full API reference (link to User Guide instead); system diagrams; operational runbook; cost estimates; performance benchmarks |
| `docs/ARCHITECTURE.md` | How It Works Inside | System diagram (ASCII/Mermaid); data flow (happy path, fan-out, orphan detection); CDK construct reference (props table, exposed properties, usage snippet); idempotency contract; DynamoDB table schema + GSI; supported event types + job ID extraction; CloudWatch metrics/alarms; architectural limitations | Value proposition / marketing; quick start / deploy commands; Java/Python API reference; operational runbook / troubleshooting; cost estimates; performance benchmarks; step-by-step tutorials |
| `docs/USER_GUIDE.md` | How to Use It Day-to-Day | Full Java API reference (builder, operations, result types, metadata format); IAM permissions for calling app; directory listing filter usage; enriched event format (JSON example); EventBridge rule examples (CDK + CLI); operational runbook (monitoring, DLQ, orphan response, log groups); troubleshooting; cost estimates | Value proposition / feature overview; quick start / deploy commands; system diagrams / internal data flow; CDK construct props; DynamoDB schema / idempotency internals; architectural decisions / limitations; step-by-step tutorials |

## Documentation Review Procedure

### Change Discovery (Pre-Step)

MUST execute before spawning review subagents. MUST run these commands in the main agent context (fast, <1 second):

1. Get last modification date of each doc file:

```bash
git log -1 --format="%aI %H" -- README.md
git log -1 --format="%aI %H" -- docs/GETTING_STARTED.md
git log -1 --format="%aI %H" -- docs/ARCHITECTURE.md
git log -1 --format="%aI %H" -- docs/USER_GUIDE.md
```

2. Compute the oldest date among the four results. Use it as `{SINCE_DATE}`.

3. Get code commits since `{SINCE_DATE}` (excluding doc files):

```bash
git log --oneline --since="{SINCE_DATE}" -- . ':!README.md' ':!docs/'
```

4. Get changed files summary:

```bash
git diff --stat $(git log -1 --format=%H --before="{SINCE_DATE}")..HEAD -- . ':!README.md' ':!docs/'
```

5. Format the output as `{RECENT_CHANGES}` — a concise list of commit subjects and affected files grouped by area (Java helper, lambdas, CDK, tests).

6. For each doc file, record its `{LAST_MODIFIED}` date from step 1.

7. Read `CHANGELOG.md` in full. Extract:
   - `{LATEST_VERSION}` — the version string from the most recent release heading.
   - `{LATEST_CHANGELOG}` — the full content of the most recent version section (everything between the first and second version headings).

- If step 3 returns no commits, SHOULD inform the user that documentation is up-to-date with the codebase and ask whether to proceed with a full review anyway.

### Subagent Stages

MUST spawn parallel subagents using the `subagent` tool with mode `blocking`. MUST NOT review files sequentially in the main agent context.

MUST use six parallel stages (one per documentation file + link validation + changelog impact) with no dependencies between them.

| Stage name | File | Role (from ownership table) |
|------------|------|----------------------------|
| readme-review | README.md | Why and How to Start |
| getting-started-review | docs/GETTING_STARTED.md | Learn by Doing |
| architecture-review | docs/ARCHITECTURE.md | How It Works Inside |
| user-guide-review | docs/USER_GUIDE.md | How to Use It Day-to-Day |
| changelog-impact-review | CHANGELOG.md | Identify documentation impact from latest release |

### Per-File Review Prompt

Each subagent MUST receive this prompt (substitute `{FILE}`, `{ROLE}`, `{MUST_CONTAIN}`, `{MUST_NOT_CONTAIN}` from the File Ownership Zones table, `{LAST_MODIFIED}`, `{RECENT_CHANGES}` from the Change Discovery pre-step, and `{LATEST_VERSION}` from step 7):

```
Read {FILE} in full. Evaluate against these criteria:

CONTEXT — Codebase changes since {FILE} was last updated ({LAST_MODIFIED}):
{RECENT_CHANGES}

LIBRARY VERSION — The latest released version is {LATEST_VERSION}. Any Maven coordinates, dependency snippets, or version references in this file MUST use this version.

1. OWNERSHIP VIOLATIONS: List any content that belongs in a different file per these rules:
   - {FILE} role: {ROLE}
   - MUST contain ONLY: {MUST_CONTAIN}
   - MUST NOT contain: {MUST_NOT_CONTAIN}

2. CROSS-REFERENCE COMPLIANCE: Check links follow the cross-reference rules:
   - README → other docs: MUST link in "Documentation" section
   - GETTING_STARTED → USER_GUIDE: MUST link for full API details
   - ARCHITECTURE/USER_GUIDE → README: MUST NOT link back
   - ARCHITECTURE ↔ USER_GUIDE: MAY cross-reference
   - ARCHITECTURE/USER_GUIDE → GETTING_STARTED: MAY reference

3. CONTENT QUALITY: Flag stale information, broken links, inconsistencies with source code.

4. DUPLICATION CANDIDATES: Extract key phrases/facts that might also appear in other docs.

5. STALENESS: Based on the recent changes above, flag documentation sections that are likely outdated or missing coverage for new/modified features. For each finding, cite the relevant commit(s).

Output a structured report with sections: VIOLATIONS, CROSS-REFS, QUALITY, DUPLICATION_CANDIDATES, STALENESS.
```

### Changelog Impact Review Prompt

The `changelog-impact-review` subagent MUST receive this prompt (substitute `{LATEST_VERSION}` and `{LATEST_CHANGELOG}` from the Change Discovery pre-step):

```
Read CHANGELOG.md in full. Focus on the latest version ({LATEST_VERSION}):

{LATEST_CHANGELOG}

For each change entry in this version, determine:

1. DOCUMENTATION IMPACT: Which documentation file(s) should be updated to reflect this change? Map each entry to the File Ownership Zones:
   - README.md: feature list, quick start, performance table, build commands
   - docs/GETTING_STARTED.md: tutorial scenarios, code examples
   - docs/ARCHITECTURE.md: system design, CDK construct props, schemas
   - docs/USER_GUIDE.md: API reference, event format, operational runbook

2. VERSION REFERENCES: The current library version is {LATEST_VERSION}. Flag any documentation that should display or reference this version (Maven coordinates, dependency snippets, badge URLs).

3. NEW FEATURES NEEDING DOCS: List any new features or breaking changes that require new documentation sections not yet covered.

4. DEPRECATIONS / REMOVALS: List any deprecated or removed functionality that should be removed from or flagged in documentation.

Output a structured report with sections: DOCUMENTATION_IMPACT, VERSION_REFERENCES, NEW_FEATURES_NEEDING_DOCS, DEPRECATIONS.
```

### Verification

- If violations are found, MUST present the consolidated report to the user before making changes.
- MUST NOT auto-fix without user confirmation.

### Cross-Reference Link Validation

In parallel of all four review stages, MUST run a fifth stage that validates link integrity across all documentation files.

| Stage name | Depends on | Purpose |
|------------|-----------|---------|
| link-validation | <none> | Verify all markdown links resolve to valid targets |

#### Link Validation Prompt

```
Read ALL of these files in full:
- README.md
- docs/GETTING_STARTED.md
- docs/ARCHITECTURE.md
- docs/USER_GUIDE.md

For every markdown link (`[text](target)` or `[text](target#anchor)`):

1. BROKEN FILE LINKS: Report links where the target file does not exist (resolve paths relative to the file containing the link).
2. BROKEN ANCHOR LINKS: Report links with `#anchor` fragments where no matching heading exists in the target file (match against slugified headings: lowercase, spaces→hyphens, strip punctuation).
3. ORPHANED CROSS-REFERENCES: Report any mandatory links from the Cross-Reference Rules table that are missing.

Output a structured report with sections: BROKEN_FILE_LINKS, BROKEN_ANCHOR_LINKS, MISSING_MANDATORY_LINKS.
Each entry MUST include: source file, line content, target, and reason.
If all links are valid, output "No discrepancies found."
```
