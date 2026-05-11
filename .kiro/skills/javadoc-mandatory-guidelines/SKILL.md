---
name: javadoc-mandatory-guidelines
description: 'Reviews, modifies, and maintains JavaDoc for the SFTP Connector Helper Library using focused multi-subagent orchestration. Use when reviewing JavaDoc quality, fixing JavaDoc issues, adding missing JavaDoc, or maintaining documentation accuracy after code changes. Do NOT use for non-JavaDoc documentation or general code review.'
---

# JavaDoc Review & Maintenance

The keywords MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, NOT RECOMMENDED, MAY, and OPTIONAL in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

## Critical Constraints

- MUST NOT modify code logic — only JavaDoc comments.
- MUST NOT leak implementation details (TTL stagger values, internal story references, DynamoDB expression syntax) into public API JavaDoc.
- MUST preserve existing `@see`, `@param`, `@return`, `@throws` tags — only add or correct, never remove without justification.
- MUST cross-reference claims against AWS Transfer Family documentation before marking JavaDoc as accurate.
- MAY connect to the AWS test environment to gather real-life examples
- MUST use the `subagent` tool (NOT `spawn_run`) to orchestrate parallel review agents.
- MUST NOT read all Java files in the main agent
- MUST ask the subagents to read the relevant files for their focused review.

## Subagent Architecture

Dispatch three focused subagents in parallel. Each agent has a single concern and produces a structured findings list.

| Agent | Role | Concern |
|-------|------|---------|
| accuracy-agent | Fact Checker | Cross-references JavaDoc claims against AWS docs and project docs |
| completeness-agent | Coverage Auditor | Identifies missing JavaDoc, missing tags, undocumented constraints |
| style-agent | Style Enforcer | Enforces project-specific JavaDoc conventions and detects leaks |

## Procedure

1. Read `references/javadoc-standards.md` to load project-specific rules.
2. Read `references/subagent-prompts.md` to load the focused prompts for each agent.
3. Identify the scope (file paths only — do NOT read file contents):
   - If user specifies files → use those file paths.
   - If user says "review all" → list all `.java` files under `helpers/java/src/main/` (paths only).
   - If user says "review changes" → run `git diff --name-only` filtered to `*.java`.
4. Dispatch three subagents in parallel using the `subagent` tool with `mode: "blocking"`:
   - Each stage MUST use role `kiro_default`.
   - Each stage prompt MUST include the list of absolute file paths to review and the corresponding prompt from `references/subagent-prompts.md`.
   - Each stage prompt MUST instruct the subagent to read the files itself using the `read` tool.
   - MUST NOT embed file contents in the prompt — subagents are responsible for loading their own data.
   - Stages have no dependencies (all run in parallel).
5. Collect results from all three agents.
6. Deduplicate findings — if multiple agents flag the same location, merge into one finding with combined rationale.
7. Present a unified report to the user with findings grouped by file, sorted by priority:
   - P1: Inaccurate claims (factually wrong)
   - P2: Missing critical information (thread safety, exceptions, constraints)
   - P3: Style violations and implementation detail leaks
8. - If user says "fix" or "apply" → apply all fixes to source files, then verify with `mvn javadoc:javadoc -f helpers/java/pom.xml` (must produce zero warnings).
   - If user says "fix P1 only" → apply only that priority level.
   - If user wants to review first → present diffs before applying.

## Verification

After applying fixes, MUST run:

```bash
cd helpers/java && mvn javadoc:javadoc -q
```

- If exit code is 0 → report success.
- If warnings/errors → fix them and re-run.
