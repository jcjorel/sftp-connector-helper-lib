---
name: git-commit
description: Enforces state-of-the-art Git commit message conventions (Conventional Commits + Chris Beams' 7 rules). Use when creating a Git commit, writing a commit message, or staging changes for commit. Do NOT use for general Git operations like branching, merging, or rebasing.
---

# Git Commit Message Skill

The keywords MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, NOT RECOMMENDED, MAY, and OPTIONAL in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

## Critical Constraints

- Subject line MUST be ≤50 characters. MUST NOT exceed 72 characters under any circumstance.
- Subject MUST use imperative mood — completes: "If applied, this commit will ___".
- Subject MUST NOT end with a period.
- First letter after `type(scope): ` MUST be uppercase.
- Body MUST wrap at 72 characters per line.
- Each commit MUST contain exactly ONE logical change.

## Commit Message Format

```
<type>(<scope>): <subject>

[body]

[footer(s)]
```

## Type Selection

| Type | When to use |
|------|-------------|
| `feat` | New feature or capability |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `style` | Formatting, whitespace, semicolons (no logic change) |
| `refactor` | Code restructuring (no new feature, no bug fix) |
| `perf` | Performance improvement |
| `test` | Adding or fixing tests |
| `build` | Build system, dependencies, packaging |
| `ci` | CI/CD pipeline configuration |
| `chore` | Maintenance tasks, tooling |
| `revert` | Reverts a previous commit |

## Scope

The `(scope)` is OPTIONAL but RECOMMENDED. Use the project's common vocabulary (e.g., `cdk`, `joiner`, `helper`, `api`).

## Breaking Changes

Indicate with `!` after type/scope AND/OR a `BREAKING CHANGE:` footer:

```
feat(api)!: Remove deprecated v1 endpoints

BREAKING CHANGE: /v1/users has been removed. Migrate to /v2/users.
```

## Footer Conventions

| Footer | Purpose |
|--------|---------|
| `Closes #123` or `Fixes #123` | Closes an issue |
| `Refs: #456, #789` | References without closing |
| `BREAKING CHANGE: <description>` | Breaking API change |
| `Co-authored-by: Name <email>` | Credit co-authors |

## Procedure

1. MUST run `git diff --cached` to review staged changes.
2. MUST verify atomicity — if multiple unrelated changes are staged, split into separate commits.
3. MUST select the type from the type table based on the nature of the change.
4. SHOULD determine scope — identify the affected component/module.
5. MUST write the subject in imperative mood, ≤50 chars, no period, capitalized after prefix.
6. SHOULD write a body if the change is non-trivial — explain what changed and why (not how).
7. SHOULD add footers — issue references, breaking change notices, co-authors.
8. MUST validate all critical constraints are satisfied before committing.
9. MUST run `git log -1 --format="%s"` after committing and verify subject length ≤50 chars and imperative mood.

## Examples

Simple fix (no body needed):

```
fix(parser): Handle empty input without crashing
```

Feature with body:

```
feat(auth): Add OAuth2 login via Google

Users can now authenticate using their Google account instead of
only email/password. This reduces signup friction for enterprise
customers who already use Google Workspace.

Closes #234
```

Breaking change:

```
feat(api)!: Return paginated responses from list endpoints

All list endpoints now return `{ items: [], nextToken: string }`
instead of a plain array. This enables efficient pagination for
large datasets.

BREAKING CHANGE: List endpoint response shape changed from array
to object with `items` and `nextToken` fields.

Refs: #567
```

## Anti-Patterns

| Bad | Reason |
|-----|--------|
| `fixed bug` | No type, past tense, vague |
| `feat: updated the login page.` | Past tense, period at end |
| `WIP` | MUST NOT commit work-in-progress to shared branches |
| `misc changes` | Meaningless, not atomic |
| Subject >72 chars | Truncated by GitHub |
| Mixed unrelated changes | Breaks bisect/revert |
