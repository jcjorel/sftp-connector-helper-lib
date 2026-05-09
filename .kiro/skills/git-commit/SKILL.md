---
name: git-commit
description: Enforces state-of-the-art Git commit message conventions (Conventional Commits + Chris Beams' 7 rules). Use when creating a Git commit, writing a commit message, or staging changes for commit.
---

# Git Commit Message Skill

Enforces best-practice commit message conventions whenever creating a Git commit. Combines the Conventional Commits v1.0.0 specification with Chris Beams' 7 rules and Tim Pope's formatting guidelines.

## Commit Message Format

```
<type>(<scope>): <subject>

[body]

[footer(s)]
```

## The 7 Rules (Always Enforced)

1. **Separate subject from body with a blank line**
2. **Limit the subject line to 50 characters** (hard limit: 72)
3. **Capitalize the subject line** — first letter after `type(scope): ` is uppercase
4. **Do not end the subject line with a period**
5. **Use the imperative mood** — "Add feature" not "Added" or "Adds" or "Adding"
6. **Wrap the body at 72 characters per line**
7. **Use the body to explain *what* and *why*, not *how***

### Imperative Mood Test

The subject must complete: "If applied, this commit will ___"

## Conventional Commits Types

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

The `(scope)` is optional but recommended. It identifies the module, component, or area affected. Use the project's common vocabulary (e.g., `cdk`, `joiner`, `helper`, `api`).

## Breaking Changes

Indicate with `!` after type/scope AND/OR a `BREAKING CHANGE:` footer:

```
feat(api)!: Remove deprecated v1 endpoints

BREAKING CHANGE: /v1/users has been removed. Migrate to /v2/users.
```

## Footer Conventions

- `Closes #123` or `Fixes #123` — closes an issue
- `Refs: #456, #789` — references without closing
- `BREAKING CHANGE: <description>` — breaking API change
- `Co-authored-by: Name <email>` — credit co-authors

## Procedure When Creating a Commit

1. **Review staged changes** — run `git diff --cached` to understand what's being committed
2. **Verify atomicity** — each commit should contain exactly ONE logical change. If multiple unrelated changes are staged, suggest splitting into separate commits.
3. **Select the type** — choose from the type table based on the nature of the change
4. **Determine scope** — identify the affected component/module
5. **Write the subject** — imperative mood, ≤50 chars, no period, capitalized after prefix
6. **Write the body if needed** — explain what changed and why (not how). Wrap at 72 chars.
7. **Add footers** — issue references, breaking change notices, co-authors
8. **Validate** — check all 7 rules are satisfied before committing

## Examples

### Simple fix (no body needed)
```
fix(parser): Handle empty input without crashing
```

### Feature with body
```
feat(auth): Add OAuth2 login via Google

Users can now authenticate using their Google account instead of
only email/password. This reduces signup friction for enterprise
customers who already use Google Workspace.

Closes #234
```

### Refactor with context
```
refactor(core): Extract validation into separate module

Validation logic was duplicated across 4 handlers. Centralizing it
reduces maintenance burden and ensures consistent error messages.
```

### Breaking change
```
feat(api)!: Return paginated responses from list endpoints

All list endpoints now return `{ items: [], nextToken: string }`
instead of a plain array. This enables efficient pagination for
large datasets.

BREAKING CHANGE: List endpoint response shape changed from array
to object with `items` and `nextToken` fields.

Refs: #567
```

### Multi-file documentation update
```
docs: Update installation instructions for Node 20
```

### Build/dependency change
```
build(deps): Upgrade AWS SDK to v2.1.0
```

## What NOT to Do

- ❌ `fixed bug` — no type, past tense, vague
- ❌ `feat: updated the login page.` — past tense, period at end
- ❌ `WIP` — never commit work-in-progress to shared branches
- ❌ `misc changes` — meaningless, not atomic
- ❌ Subject over 72 characters — will be truncated by GitHub
- ❌ Mixing unrelated changes in one commit — breaks bisect/revert
