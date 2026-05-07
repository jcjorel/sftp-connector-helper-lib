package io.github.jcjorel.sftpconnectorhelper;

/**
 * Sealed interface representing the outcome of an SFTP Connector operation
 * that includes metadata correlation.
 *
 * @param <T> the type of the SDK response
 */
public sealed interface SftpOperationResult<T> {

    /**
     * SDK call and metadata write both succeeded.
     */
    record Success<T>(T response) implements SftpOperationResult<T> {}

    /**
     * SDK call succeeded but DynamoDB metadata write failed.
     */
    record MetadataWriteFailed<T>(T response, String jobId, Exception cause) implements SftpOperationResult<T> {}

    /**
     * SDK call succeeded but metadata already existed for this jobId (caller bug).
     */
    record MetadataAlreadyExists<T>(T response, String jobId) implements SftpOperationResult<T> {}
}
