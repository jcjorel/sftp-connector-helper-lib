---
name: maven-publish
description: 'Guides Maven Central publishing for the sftp-connector-helper Java library. Covers version bumping, build verification, and deploy commands. Use when publishing a new version, bumping versions, releasing to Maven Central, or troubleshooting publish failures. Do NOT use for general Maven build issues unrelated to publishing.'
---

The keywords MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, RECOMMENDED, NOT RECOMMENDED, MAY, and OPTIONAL in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and [RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

# Maven Central Publishing

## Project Coordinates

| Field | Value |
|-------|-------|
| groupId | `io.github.jcjorel` |
| artifactId | `sftp-connector-helper` |
| Published POM | `helpers/java/pom.xml` |
| Parent POM | `pom.xml` (root, aggregator only — NOT published) |
| Packaging | `jar` |
| Java version | 21 |
| Publishing portal | Sonatype Central Portal (`central-publishing-maven-plugin` 0.6.0) |
| Server ID in settings.xml | `central` |
| Auto-publish | `true` (artifacts go live after validation) |

## POM Files Requiring Version Sync

Both files MUST have their `<version>` updated in lockstep:

1. `pom.xml` (root) — `<version>X.Y.Z</version>` at line ~9
2. `helpers/java/pom.xml` — `<parent><version>X.Y.Z</version></parent>` at line ~9

The integration test POM (`tests/integration/java/pom.xml`) also references the parent version and MUST be updated.

## Publishing Plugins Already Configured

The `helpers/java/pom.xml` includes all required plugins:

| Plugin | Version | Purpose |
|--------|---------|---------|
| `maven-source-plugin` | 3.3.1 | Generates `-sources.jar` |
| `maven-javadoc-plugin` | 3.11.2 | Generates `-javadoc.jar` |
| `maven-gpg-plugin` | 3.2.7 | Signs all artifacts with GPG |
| `central-publishing-maven-plugin` | 0.6.0 | Publishes to Sonatype Central Portal |

## Publish Procedure

1. **CHANGELOG verification and date stamp (MUST — hard stop if missing):** Read `CHANGELOG.md` and confirm it contains an entry for the version being released (e.g., `## [X.Y.Z]` or `## X.Y.Z`). If no matching entry exists, ABORT the entire procedure immediately with a clear error message and DO NOT proceed to any subsequent step. If the entry does NOT already have a date suffix, update the heading to `## [X.Y.Z] - YYYY-MM-DD` using today's date (ISO 8601 format).
2. Verify the working tree is clean: `git status` MUST show no uncommitted changes.
3. Update version in all three POMs (root, helpers/java, tests/integration/java).
4. Run full build with tests from `helpers/java/`:
   ```bash
   cd helpers/java && mvn clean verify
   ```
5. Verify build succeeds and all tests pass.
6. Commit the version bump and CHANGELOG date update with message: `chore: release version X.Y.Z`
7. Tag the release: `git tag vX.Y.Z`
8. Deploy to Maven Central from `helpers/java/`:
   ```bash
   cd helpers/java && mvn clean deploy
   ```
9. Verify publication at https://central.sonatype.com/artifact/io.github.jcjorel/sftp-connector-helper
10. Push commit and tag: `git push && git push --tags`

## Prerequisites (user's local environment)

- GPG signing key available (`gpg --list-secret-keys` MUST show a key)
- `~/.m2/settings.xml` MUST contain:
  ```xml
  <servers>
    <server>
      <id>central</id>
      <username>SONATYPE_PORTAL_TOKEN_USERNAME</username>
      <password>SONATYPE_PORTAL_TOKEN_PASSWORD</password>
    </server>
  </servers>
  ```
- Token generated at https://central.sonatype.com/account

## Troubleshooting

- If GPG signing fails: check `gpg-agent` is running and key is not expired.
- If deploy returns 401: regenerate token at Sonatype Central Portal.
- If validation fails after upload: check that `-sources.jar`, `-javadoc.jar`, and `.asc` signatures are all present in the deployment bundle.
- If JavaDoc generation fails: fix JavaDoc errors before publishing (the build MUST pass `javadoc:jar`).

## Constraints

- MUST NOT publish without a matching version entry in `CHANGELOG.md`. This is a hard gate — abort immediately if missing.
- MUST NOT publish from a dirty working tree.
- MUST NOT skip GPG signing (Maven Central rejects unsigned artifacts).
- MUST NOT use `-DskipTests` during the publish build.
- SHOULD create a GitHub release after successful publication.
