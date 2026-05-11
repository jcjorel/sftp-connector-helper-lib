package io.github.jcjorel.sftpconnectorhelper;

import software.amazon.awssdk.services.transfer.model.TransferResponse;

/**
 * Thrown when the DynamoDB metadata write fails after a successful Transfer Family SDK call.
 *
 * <p>The SFTP operation (transfer, listing, move, or delete) has already been initiated
 * successfully — the file transfer is in progress. However, the metadata correlation
 * will not be available for downstream enriched events.</p>
 *
 * <p>Callers who want to proceed without metadata correlation can catch this exception
 * and use {@link #getSdkResponse()} to access the original SDK response:</p>
 * <pre>{@code
 * try {
 *     StartFileTransferResponse response = helper.startFileTransfer(request, metadata);
 * } catch (MetadataWriteException e) {
 *     // Transfer is in progress — proceed without enriched events
 *     var response = (StartFileTransferResponse) e.getSdkResponse();
 *     log.warn("Transfer {} started but metadata lost", e.getJobId(), e);
 * }
 * }</pre>
 */
public class MetadataWriteException extends SftpConnectorHelperException {

    private final String jobId;
    private final TransferResponse sdkResponse;

    /**
     * Constructs a new MetadataWriteException.
     *
     * @param message     the detail message describing the failure
     * @param jobId       the job identifier from the SDK response (transfer ID, listing ID, etc.)
     * @param sdkResponse the raw SDK response from the successful Transfer Family call.
     *                    Callers must cast to the concrete type matching the method invoked
     *                    (e.g., {@code (StartFileTransferResponse) e.getSdkResponse()}).
     *                    This cast is always safe because the response type is determined
     *                    by the method that threw this exception.
     * @param cause       the underlying exception that caused the metadata write failure
     */
    public MetadataWriteException(String message, String jobId, TransferResponse sdkResponse, Throwable cause) {
        super(message, cause);
        this.jobId = jobId;
        this.sdkResponse = sdkResponse;
    }

    /**
     * Returns the job identifier from the SDK response.
     *
     * @return the transfer ID, listing ID, move ID, or delete ID
     */
    public String getJobId() {
        return jobId;
    }

    /**
     * Returns the raw SDK response from the successful Transfer Family call.
     *
     * <p>Callers must cast to the concrete type matching the method that threw this exception.
     * For example, if {@code startFileTransfer()} threw this exception, cast to
     * {@code StartFileTransferResponse}. This cast is always safe because the response type
     * is determined by the method invoked.</p>
     *
     * @return the SDK response (never null)
     */
    public TransferResponse getSdkResponse() {
        return sdkResponse;
    }
}
