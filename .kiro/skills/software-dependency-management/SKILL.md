---
name: software-dependency-management
description: 'Manages software dependency lifecycle: audits current versions, discovers latest stable releases from authoritative sources (repo1.maven.org, PyPI JSON API, GitHub Releases API), and applies upgrades. Use when checking for outdated dependencies, upgrading dependencies, or auditing dependency versions. Do NOT use for general build issues or non-dependency configuration.'
---

The keywords MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, NOT RECOMMENDED, MAY, and OPTIONAL in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

## Critical Constraints

- MUST query authoritative sources only — NEVER guess or use training data for version numbers.
- MUST distinguish stable releases from pre-releases (alpha, beta, RC, M milestones).
- MUST NOT upgrade to pre-release versions unless the user explicitly requests it.
- MUST present findings before applying changes — never auto-upgrade without confirmation.
- MUST verify builds pass after applying upgrades.
- MUST treat Lambda `requirements.txt` files as the source of truth for pinned versions — edit directly to upgrade.
- MUST update requirements.txt for BOTH Lambdas when upgrading shared dependencies (they pin the same boto3 version).

## Authoritative Sources

| Ecosystem | Source | Method |
|-----------|--------|--------|
| Maven (dependencies + plugins) | repo1.maven.org | `curl -s "https://repo1.maven.org/maven2/<GROUP_PATH>/<ARTIFACT>/maven-metadata.xml"` — parse ALL `<version>` elements, filter pre-releases, pick highest stable |
| AWS SDK for Java v2 | GitHub Releases API | `curl -s "https://api.github.com/repos/aws/aws-sdk-java-v2/releases?per_page=5"` — first non-prerelease entry |
| Python (PyPI) | PyPI JSON API | `curl -s "https://pypi.org/pypi/<PACKAGE>/json"` — read `info.version` for latest stable; scan `releases` keys for major version discovery |

MUST NOT use `search.maven.org` — its search index lags behind actual repository state by days or weeks.

## Pre-release Detection

MUST filter versions containing any of these tokens (case-insensitive): `alpha`, `beta`, `RC`, `M1`–`M9`, `SNAPSHOT`, `cr`, `preview`.

## Version Comparison Logic

MUST parse versions as `MAJOR.MINOR.PATCH` (ignoring qualifiers). MUST classify delta:
- Same major+minor, different patch → `patch`
- Same major, different minor → `minor`
- Different major → `major` ⚠️ (MUST flag for manual review)

MUST use numeric comparison on each segment (not lexicographic). Example: `2.9.0 < 2.10.0 < 2.21.3`.

## Dependency Manifest Locations

| Component | File | Format |
|-----------|------|--------|
| Java deps (centralized) | `pom.xml` (root) | `<properties>` block with version variables |
| Java helper library | `helpers/java/pom.xml` | Inherits from parent |
| Integration tests | `tests/integration/java/pom.xml` | Inherits from parent |
| CDK construct | `cdk/pyproject.toml` | `[project.dependencies]` constraints, `[build-system]` pin, `[project.optional-dependencies]` pins |
| Lambda — joiner | `lambdas/joiner/requirements.txt` | Pinned `boto3` version, installed via `pip install --target` |
| Lambda — event-writer | `lambdas/event-writer/requirements.txt` | Pinned `boto3` version, installed via `pip install --target` |

## Workflow — Audit Dependencies

1. Read all manifest files listed in the Dependency Manifest Locations table.
2. Extract each dependency with its current pinned or constrained version.
3. Partition dependencies into ecosystem groups:
   - **Maven group**: all dependencies/plugins from `pom.xml` files
   - **PyPI group**: all Python packages from `pyproject.toml` files
   - **GitHub Releases group**: AWS SDK for Java v2 and any GitHub-sourced deps
4. Spawn one subagent per ecosystem in PARALLEL using the `subagent` tool (see Subagent Orchestration).
5. Merge subagent results into a unified comparison table with columns: Dependency | Current | Latest Same-Major | Latest Any-Major | Delta.
6. MUST flag dependencies more than one minor version behind as "notable upgrades."
7. Present the merged table to the user.

## Subagent Orchestration

MUST use the `subagent` tool with `mode: "blocking"` and NO `depends_on` between ecosystem stages (enabling parallel execution).

Each subagent MUST receive in its prompt:
- The list of dependencies with current versions for its ecosystem
- The authoritative source URL pattern and query method
- Pre-release detection rules
- Version sorting rules (numeric, not lexicographic)
- Major version discovery instructions

Each subagent MUST return a markdown table with columns: `Dependency | Current | Latest Same-Major | Latest Any-Major | Delta`.

### Subagent Stage Definitions

```yaml
task: "Audit dependency versions across all ecosystems"
mode: blocking
stages:
  - name: maven-research
    role: kiro_default
    prompt_template: |
      Research latest stable versions for these Maven dependencies:
      {maven_deps_table}

      For EACH dependency:
      1. Run: curl -s "https://repo1.maven.org/maven2/<GROUP_PATH>/<ARTIFACT>/maven-metadata.xml"
         where GROUP_PATH replaces dots with slashes in the groupId.
      2. Parse ALL <version> elements from the <versions> list.
      3. Filter out pre-releases (tokens: alpha, beta, RC, M1-M9, SNAPSHOT, cr, preview — case-insensitive).
      4. Sort remaining by MAJOR.MINOR.PATCH descending using NUMERIC comparison.
      5. Latest Same-Major = highest stable with same MAJOR as current.
      6. Latest Any-Major = highest stable across all MAJORs.
      7. Delta = patch|minor|major (flag major with ⚠️).

      MUST NOT use search.maven.org. MUST NOT guess versions.
      Return a markdown table: Dependency | Current | Latest Same-Major | Latest Any-Major | Delta

  - name: pypi-research
    role: kiro_default
    prompt_template: |
      Research latest stable versions for these Python packages:
      {pypi_deps_table}

      For EACH package:
      1. Run: curl -s "https://pypi.org/pypi/<PACKAGE>/json"
      2. Read info.version for latest stable. If it contains pre-release tokens, scan
         releases object keys, filter to stable, pick highest.
      3. For major version discovery: scan ALL releases keys for versions with MAJOR > current.
      4. Filter pre-releases (tokens: alpha, beta, RC, M1-M9, SNAPSHOT, cr, preview — case-insensitive).
      5. Latest Same-Major = highest stable with same MAJOR as current.
      6. Latest Any-Major = highest stable across all MAJORs.
      7. Delta = patch|minor|major (flag major with ⚠️).

      MUST NOT guess versions.
      Return a markdown table: Dependency | Current | Latest Same-Major | Latest Any-Major | Delta

  - name: github-releases-research
    role: kiro_default
    prompt_template: |
      Research latest stable versions for these GitHub-sourced dependencies:
      {github_deps_table}

      For EACH dependency:
      1. Run: curl -s "https://api.github.com/repos/<OWNER>/<REPO>/releases?per_page=10"
      2. Find the first entry where prerelease is false and tag_name does not contain
         pre-release tokens (alpha, beta, RC, M1-M9, SNAPSHOT, cr, preview).
      3. Extract version from tag_name (strip leading 'v' if present).
      4. Latest Same-Major = highest stable with same MAJOR as current.
      5. Latest Any-Major = highest stable across all MAJORs.
      6. Delta = patch|minor|major (flag major with ⚠️).

      MUST NOT guess versions.
      Return a markdown table: Dependency | Current | Latest Same-Major | Latest Any-Major | Delta
```

### Subagent Failure Handling

- If a subagent fails (network error, rate limit, timeout), MUST report which ecosystem failed.
- MUST present partial results from successful subagents immediately.
- MUST offer to retry the failed ecosystem only.
- MUST NOT block the entire audit on a single ecosystem failure.

## Workflow — Major Version Discovery

Handled within each subagent (step 6 in Maven/PyPI, step 4-5 in GitHub). The main agent MUST:

1. Scan merged results for any row where Delta = `major`.
2. MUST report these as **major upgrade opportunities** ⚠️.
3. MUST NOT recommend major upgrades without explicit user confirmation — only report their existence.

## Workflow — Upgrade Dependencies

1. MUST run the audit workflow first.
2. MUST ask the user which dependencies to upgrade (all, or a specific subset).
3. MUST apply upgrades according to ecosystem:
   - **Java**: Update the version property in root `pom.xml` `<properties>`.
   - **CDK pyproject.toml**: Update the version constraint if pinned. SHOULD NOT modify `>=` constraints unless the floor needs raising.
   - **Lambda requirements.txt**: Update the pinned version directly in each Lambda's `requirements.txt`:
     ```bash
     # Edit lambdas/joiner/requirements.txt and lambdas/event-writer/requirements.txt
     # e.g. boto3==1.44.0
     ```
     MUST update both Lambdas to keep them in sync.
4. MUST verify the build passes:
   ```bash
   make build
   ```
5. MUST run unit tests:
   ```bash
   make test
   ```
6. If build or tests fail, MUST diagnose and report. MUST NOT silently revert changes.

## Workflow — Upgrade a Single Dependency

1. Identify the dependency and its manifest location.
2. Query the authoritative source for the latest stable version (MAY use a single subagent or direct query for one dependency).
3. Apply the version change.
4. Run `make build` and `make test`.
5. Report the result to the user.
