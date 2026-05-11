# JavaDoc Standards — SFTP Connector Helper Library

## Scope

These rules apply to all Java source files under `helpers/java/src/main/java/`.

## Required Coverage

| Element | JavaDoc Required? |
|---------|-------------------|
| Public class/interface/enum/record | YES — class-level summary + purpose |
| Public method | YES — summary + all `@param`, `@return`, `@throws` |
| Public constant (`static final`) | YES — one-line description |
| Package-private method | RECOMMENDED if non-trivial |
| Private method | NOT REQUIRED |
| Internal classes (`internal` package) | YES — MUST state "Internal class — not part of the public API." |

## Tag Requirements

| Tag | When Required |
|-----|---------------|
| `@param` | Every parameter, including generics `@param <T>` |
| `@return` | Every non-void method (except constructors) |
| `@throws` | Every checked exception + every unchecked exception the caller should handle |
| `@see` | Cross-reference related classes when the relationship is non-obvious |
| `@since` | OPTIONAL for this project (single version so far) |

## Content Rules

### MUST include

- What the method/class does (first sentence = summary sentence for Javadoc index).
- Constraints on parameters (nullability, valid ranges, format requirements).
- Side effects (network calls, state mutations, resource acquisition).
- Thread-safety guarantees at class level.
- Usage example (`<pre>{@code ...}</pre>`) on primary entry-point classes.

### MUST NOT include

- Implementation details that may change (DynamoDB expression syntax, TTL arithmetic, internal sentinel values).
- Internal project references (story IDs, sprint names, ticket numbers).
- Redundant restatements of the method signature ("This method takes a String and returns a Result").
- `@author` tags (use git blame instead).

### Style

- First sentence MUST be a concise summary in third-person declarative: "Starts a file transfer with metadata correlation."
- MUST NOT start with "This method..." or "This class...".
- Use `{@code text}` for inline code references, `{@link ClassName}` for type references.
- Use `<p>` for paragraph breaks within descriptions.
- Use `<ul><li>` for lists within descriptions.
- Use `<pre>{@code ...}</pre>` for multi-line code examples.

## AWS-Specific Accuracy Rules

- API response field names MUST match AWS SDK exactly: `transferId`, `listingId`, `moveId`, `deleteId`.
- Event detail-type values MUST match AWS documentation exactly (case-sensitive): e.g., "SFTP Connector File Send Completed".
- Event field names MUST use kebab-case as in EventBridge: `transfer-id`, `file-transfer-id`, `connector-id`, `status-code`.
- When referencing AWS behavior, MUST distinguish between what the SDK does vs. what this library adds.

## Exception Documentation Pattern

For methods that call AWS SDK operations:

```java
/**
 * @throws IllegalArgumentException if request is null or metadata is invalid
 *         (not a JSON object, exceeds 8 KB, or exceeds 50 levels nesting depth)
 * @throws software.amazon.awssdk.core.exception.SdkException
 *         if the Transfer Family API call fails (network error, throttling, access denied)
 */
```

## Metadata Constraints (for reference in JavaDoc)

- Must be a valid JSON object (not array, not primitive, not null).
- Maximum size: 8,000 bytes (UTF-8 encoded).
- Maximum nesting depth: 50 levels.
