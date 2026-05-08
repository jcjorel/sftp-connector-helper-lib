package io.github.jcjorel.sftpconnectorhelper;

/**
 * Sealed interface representing the outcome of an SFTP Connector operation
 * that includes metadata correlation.
 *
 * <p>Every wrapper method in {@link SftpConnectorHelper} returns one of three variants:
 * <ul>
 *   <li>{@link Success} — both the SDK call and metadata write succeeded</li>
 *   <li>{@link MetadataWriteFailed} — SDK call succeeded but DynamoDB write failed (the transfer is in progress)</li>
 *   <li>{@link MetadataAlreadyExists} — SDK call succeeded but metadata was already written for this job ID</li>
 * </ul>
 *
 * <h2>Pattern Matching</h2>
 * <pre>{@code
 * switch (result) {
 *     case SftpOperationResult.Success<T> s -> handleSuccess(s.response());
 *     case SftpOperationResult.MetadataWriteFailed<T> f -> log.warn("metadata lost", f.cause());
 *     case SftpOperationResult.MetadataAlreadyExists<T> e -> log.error("duplicate: " + e.jobId());
 * }
 * }</pre>
 *
 * @param <T> the type of the SDK response
 */
public sealed interface SftpOperationResult<T> {

    /**
     * SDK call and metadata write both succeeded.
     *
     * @param response the SDK response from the Transfer Family operation
     */
    record Success<T>(T response) implements SftpOperationResult<T> {}

    /**
     * SDK call succeeded but DynamoDB metadata write failed.
     * The transfer is in progress but metadata won't be joined to downstream events.
     *
     * @param response the SDK response from the Transfer Family operation
     * @param jobId    the job identifier returned by the SDK
     * @param cause    the exception that caused the write failure
     */
    record MetadataWriteFailed<T>(T response, String jobId, Exception cause) implements SftpOperationResult<T> {}

    /**
     * SDK call succeeded but metadata already existed for this jobId.
     * This typically indicates a caller bug (duplicate invocation) or a retry.
     *
     * @param response the SDK response from the Transfer Family operation
     * @param jobId    the job identifier that already had metadata
     */
    record MetadataAlreadyExists<T>(T response, String jobId) implements SftpOperationResult<T> {}
}
