package io.github.jcjorel.sftpconnectorhelper;

import io.github.jcjorel.sftpconnectorhelper.internal.MetadataValidator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.transfer.TransferClient;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingRequest;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingResponse;
import software.amazon.awssdk.services.transfer.model.StartFileTransferRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferResponse;
import software.amazon.awssdk.services.transfer.model.StartRemoteDeleteRequest;
import software.amazon.awssdk.services.transfer.model.StartRemoteDeleteResponse;
import software.amazon.awssdk.services.transfer.model.StartRemoteMoveRequest;
import software.amazon.awssdk.services.transfer.model.StartRemoteMoveResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Helper library for AWS Transfer Family SFTP Connector operations
 * with automatic metadata correlation via DynamoDB.
 *
 * <p>Wraps each Transfer Family SDK call (StartFileTransfer, StartDirectoryListing,
 * StartRemoteMove, StartRemoteDelete) to first execute the operation, then write
 * caller-supplied business metadata to DynamoDB using a conditional {@code UpdateItem}.
 * Downstream, the Joiner Lambda joins this metadata with Transfer Family events and
 * publishes enriched events to a dedicated EventBridge bus.</p>
 *
 * <p>Implements {@link AutoCloseable} — use try-with-resources or call {@link #close()}
 * to release the underlying SDK clients when done.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (SftpConnectorHelper helper = SftpConnectorHelper.builder().build()) {
 *     var result = helper.startFileTransfer(request, "{\"orderId\":\"ORD-001\"}");
 * }
 * }</pre>
 *
 * @see SftpConnectorHelperBuilder
 * @see SftpOperationResult
 */
public final class SftpConnectorHelper implements AutoCloseable {

    public static final String DEFAULT_TABLE_NAME = "sftp-connector-helper";
    public static final Duration DEFAULT_TTL_DURATION = Duration.ofHours(24);

    private final String tableName;
    private final Duration ttlDuration;
    private final DynamoDbClient dynamoDbClient;
    private final TransferClient transferClient;

    SftpConnectorHelper(String tableName, Duration ttlDuration, DynamoDbClient dynamoDbClient, TransferClient transferClient) {
        this.tableName = tableName;
        this.ttlDuration = ttlDuration;
        this.dynamoDbClient = dynamoDbClient;
        this.transferClient = transferClient;
    }

    /**
     * Creates a new builder for configuring and constructing an {@link SftpConnectorHelper} instance.
     *
     * @return a new builder instance
     */
    public static SftpConnectorHelperBuilder builder() {
        return new SftpConnectorHelperBuilder();
    }

    /** Returns the DynamoDB table name used for metadata storage. */
    public String getTableName() {
        return tableName;
    }

    /** Returns the TTL duration applied to DynamoDB records. */
    public Duration getTtlDuration() {
        return ttlDuration;
    }

    /** Returns the DynamoDB client used by this helper. */
    public DynamoDbClient getDynamoDbClient() {
        return dynamoDbClient;
    }

    /** Returns the Transfer Family client used by this helper. */
    public TransferClient getTransferClient() {
        return transferClient;
    }

    /** Closes the underlying DynamoDB and Transfer Family SDK clients. */
    @Override
    public void close() {
        dynamoDbClient.close();
        transferClient.close();
    }

    /**
     * Starts a file transfer with metadata correlation.
     * Uses staggered TTL (+3600s) since this creates the master fan-out record.
     *
     * @param request  the StartFileTransfer SDK request
     * @param metadata the business metadata JSON string to correlate
     * @return the operation result indicating success or specific failure mode
     * @throws IllegalArgumentException if request is null or metadata is invalid
     */
    public SftpOperationResult<StartFileTransferResponse> startFileTransfer(StartFileTransferRequest request, String metadata) {
        return startFileTransfer(request, metadata, null);
    }

    /**
     * Starts a file transfer with metadata correlation and event emission options.
     * Uses staggered TTL (+3600s) since this creates the master fan-out record.
     *
     * <p>When {@code options} specifies a mode other than {@link EventEmissionMode#INDIVIDUAL_FILE_EVENTS_ONLY},
     * batch-tracking fields ({@code emissionMode}, {@code expectedFiles}, {@code transferDirection})
     * are written atomically with the metadata to the master DynamoDB record.</p>
     *
     * @param request  the StartFileTransfer SDK request
     * @param metadata the business metadata JSON string to correlate
     * @param options  event emission options, or {@code null} for default behavior
     * @return the operation result indicating success or specific failure mode
     * @throws IllegalArgumentException if request is null or metadata is invalid
     */
    public SftpOperationResult<StartFileTransferResponse> startFileTransfer(StartFileTransferRequest request, String metadata, FileTransferOptions options) {
        if (request == null) {
            throw new IllegalArgumentException("StartFileTransferRequest must not be null");
        }
        validateMetadata(metadata);
        StartFileTransferResponse response = transferClient.startFileTransfer(request);

        EventEmissionMode mode = (options != null) ? options.emissionMode() : EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY;
        if (mode == EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY) {
            return writeMetadataAndReturn(response, response.transferId(), metadata, true);
        }
        return writeMetadataWithBatchFields(response, response.transferId(), metadata, request, mode);
    }

    /**
     * Starts a directory listing with metadata correlation.
     * Uses standard TTL (no stagger).
     *
     * @param request  the StartDirectoryListing SDK request
     * @param metadata the business metadata JSON string to correlate
     * @return the operation result indicating success or specific failure mode
     * @throws IllegalArgumentException if request is null or metadata is invalid
     */
    public SftpOperationResult<StartDirectoryListingResponse> startDirectoryListing(StartDirectoryListingRequest request, String metadata) {
        if (request == null) {
            throw new IllegalArgumentException("StartDirectoryListingRequest must not be null");
        }
        validateMetadata(metadata);
        StartDirectoryListingResponse response = transferClient.startDirectoryListing(request);
        return writeMetadataAndReturn(response, response.listingId(), metadata, false);
    }

    /**
     * Starts a remote move with metadata correlation.
     * Uses standard TTL (no stagger).
     *
     * @param request  the StartRemoteMove SDK request
     * @param metadata the business metadata JSON string to correlate
     * @return the operation result indicating success or specific failure mode
     * @throws IllegalArgumentException if request is null or metadata is invalid
     */
    public SftpOperationResult<StartRemoteMoveResponse> startRemoteMove(StartRemoteMoveRequest request, String metadata) {
        if (request == null) {
            throw new IllegalArgumentException("StartRemoteMoveRequest must not be null");
        }
        validateMetadata(metadata);
        StartRemoteMoveResponse response = transferClient.startRemoteMove(request);
        return writeMetadataAndReturn(response, response.moveId(), metadata, false);
    }

    /**
     * Starts a remote delete with metadata correlation.
     * Uses standard TTL (no stagger).
     *
     * @param request  the StartRemoteDelete SDK request
     * @param metadata the business metadata JSON string to correlate
     * @return the operation result indicating success or specific failure mode
     * @throws IllegalArgumentException if request is null or metadata is invalid
     */
    public SftpOperationResult<StartRemoteDeleteResponse> startRemoteDelete(StartRemoteDeleteRequest request, String metadata) {
        if (request == null) {
            throw new IllegalArgumentException("StartRemoteDeleteRequest must not be null");
        }
        validateMetadata(metadata);
        StartRemoteDeleteResponse response = transferClient.startRemoteDelete(request);
        return writeMetadataAndReturn(response, response.deleteId(), metadata, false);
    }

    private <T> SftpOperationResult<T> writeMetadataAndReturn(T response, String jobId, String metadata, boolean staggerTtl) {
        if (jobId == null || jobId.isEmpty()) {
            throw new IllegalStateException("SDK response returned null or empty job ID");
        }
        long ttlEpochSeconds = Instant.now().getEpochSecond() + ttlDuration.toSeconds() + (staggerTtl ? 3600 : 0);

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                .updateExpression("SET metadata = :m, #t = :t")
                .expressionAttributeNames(Map.of("#t", "ttl"))
                .expressionAttributeValues(Map.of(
                        ":m", AttributeValue.builder().s(metadata).build(),
                        ":t", AttributeValue.builder().n(String.valueOf(ttlEpochSeconds)).build()
                ))
                .conditionExpression("attribute_not_exists(metadata)")
                .build();

        try {
            dynamoDbClient.updateItem(updateRequest);
            return new SftpOperationResult.Success<>(response);
        } catch (ConditionalCheckFailedException e) {
            return new SftpOperationResult.MetadataAlreadyExists<>(response, jobId);
        } catch (DynamoDbException e) {
            return new SftpOperationResult.MetadataWriteFailed<>(response, jobId, e);
        }
    }

    private SftpOperationResult<StartFileTransferResponse> writeMetadataWithBatchFields(
            StartFileTransferResponse response, String jobId, String metadata,
            StartFileTransferRequest request, EventEmissionMode mode) {
        if (jobId == null || jobId.isEmpty()) {
            throw new IllegalStateException("SDK response returned null or empty job ID");
        }
        long ttlEpochSeconds = Instant.now().getEpochSecond() + ttlDuration.toSeconds() + 3600;

        boolean isSend = request.hasSendFilePaths() && !request.sendFilePaths().isEmpty();
        String transferDirection = isSend ? "SEND" : "RETRIEVE";
        int expectedFiles = isSend ? request.sendFilePaths().size() : request.retrieveFilePaths().size();

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                .updateExpression("SET metadata = :m, #t = :t, emissionMode = :em, expectedFiles = :ef, transferDirection = :td")
                .expressionAttributeNames(Map.of("#t", "ttl"))
                .expressionAttributeValues(Map.of(
                        ":m", AttributeValue.builder().s(metadata).build(),
                        ":t", AttributeValue.builder().n(String.valueOf(ttlEpochSeconds)).build(),
                        ":em", AttributeValue.builder().s(mode.name()).build(),
                        ":ef", AttributeValue.builder().n(String.valueOf(expectedFiles)).build(),
                        ":td", AttributeValue.builder().s(transferDirection).build()
                ))
                .conditionExpression("attribute_not_exists(metadata)")
                .build();

        try {
            dynamoDbClient.updateItem(updateRequest);
            return new SftpOperationResult.Success<>(response);
        } catch (ConditionalCheckFailedException e) {
            return new SftpOperationResult.MetadataAlreadyExists<>(response, jobId);
        } catch (DynamoDbException e) {
            return new SftpOperationResult.MetadataWriteFailed<>(response, jobId, e);
        }
    }

    /**
     * Validates metadata before SDK calls.
     * Package-private — called by wrapper methods in Stories 1.3/1.4.
     */
    void validateMetadata(String metadata) {
        MetadataValidator.validate(metadata);
    }
}
