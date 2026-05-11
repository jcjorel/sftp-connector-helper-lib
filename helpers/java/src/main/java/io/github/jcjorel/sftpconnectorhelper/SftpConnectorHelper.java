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
import software.amazon.awssdk.services.transfer.model.TransferResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Provides metadata-correlated wrappers around AWS Transfer Family SFTP Connector operations.
 *
 * <p>Wraps each Transfer Family SDK call (StartFileTransfer, StartDirectoryListing,
 * StartRemoteMove, StartRemoteDelete) to first execute the operation, then persist
 * caller-supplied business metadata. Downstream, the framework joins this metadata
 * with Transfer Family events and publishes enriched events to a dedicated EventBridge bus.</p>
 *
 * <p>Implements {@link AutoCloseable} — use try-with-resources or call {@link #close()}
 * to release the underlying SDK clients when done.</p>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. It holds immutable configuration and
 * thread-safe AWS SDK clients. Multiple threads may invoke operations concurrently.</p>
 *
 * <h2>Exception Contract</h2>
 * <table>
 *   <caption>Exception behavior for all operations</caption>
 *   <tr><th>Condition</th><th>Behavior</th></tr>
 *   <tr><td>Metadata validation fails</td><td>{@link IllegalArgumentException}</td></tr>
 *   <tr><td>Request is null</td><td>{@link IllegalArgumentException}</td></tr>
 *   <tr><td>SDK call fails (network, throttle, auth)</td><td>{@link software.amazon.awssdk.core.exception.SdkException} propagates</td></tr>
 *   <tr><td>SDK returns null/empty job ID</td><td>{@link IllegalStateException}</td></tr>
 *   <tr><td>DynamoDB write fails (throttle, error)</td><td>{@link MetadataWriteException}</td></tr>
 *   <tr><td>DynamoDB condition check fails</td><td>{@link MetadataWriteException} with "Unexpected duplicate metadata" message</td></tr>
 *   <tr><td>batchTimeout &gt;= effective TTL</td><td>{@link IllegalArgumentException}</td></tr>
 * </table>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (SftpConnectorHelper helper = SftpConnectorHelper.builder().build()) {
 *     StartFileTransferRequest request = StartFileTransferRequest.builder()
 *         .connectorId("c-1234567890abcdef0")
 *         .sendFilePaths("/outbound/invoice.csv")
 *         .build();
 *     StartFileTransferResponse response =
 *         helper.startFileTransfer(request, "{\"orderId\":\"ORD-001\"}");
 * }
 * }</pre>
 *
 * @see SftpConnectorHelperBuilder
 * @see MetadataWriteException
 */
public final class SftpConnectorHelper implements AutoCloseable {

    /**
     * Default DynamoDB table name used for metadata storage.
     *
     * @see SftpConnectorHelperBuilder#tableName(String)
     */
    public static final String DEFAULT_TABLE_NAME = "sftp-connector-helper";
    /** Default time-to-live duration (24 hours) for metadata records. */
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

    /**
     * Returns the DynamoDB table name used for metadata storage.
     *
     * @return the table name
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Returns the TTL duration applied to metadata records.
     *
     * @return the TTL duration
     */
    public Duration getTtlDuration() {
        return ttlDuration;
    }

    /**
     * Returns the DynamoDB client used by this helper.
     *
     * @return the DynamoDB client
     */
    public DynamoDbClient getDynamoDbClient() {
        return dynamoDbClient;
    }

    /**
     * Returns the Transfer Family client used by this helper.
     *
     * @return the Transfer client
     */
    public TransferClient getTransferClient() {
        return transferClient;
    }

    /**
     * Closes the underlying DynamoDB and Transfer Family SDK clients, releasing network resources.
     *
     * <p>After this method returns, any subsequent operation calls will fail.
     * This method is idempotent — calling it multiple times has no additional effect.</p>
     */
    @Override
    public void close() {
        dynamoDbClient.close();
        transferClient.close();
    }

    /**
     * Starts a file transfer with metadata correlation.
     *
     * @param request  the StartFileTransfer SDK request
     * @param metadata the business metadata JSON string to correlate. Must be a valid JSON object
     *                 (not array, not primitive, not {@code null}), maximum 8,000 bytes UTF-8 encoded,
     *                 maximum nesting depth of 50 levels.
     * @return the SDK response from the Transfer Family operation
     * @throws IllegalArgumentException if request is null or metadata is invalid
     * @throws MetadataWriteException if the DynamoDB metadata write fails after a successful SDK call
     * @throws software.amazon.awssdk.core.exception.SdkException
     *         if the Transfer Family API call fails (network error, throttling, access denied)
     */
    public StartFileTransferResponse startFileTransfer(StartFileTransferRequest request, String metadata) {
        return startFileTransfer(request, metadata, null);
    }

    /**
     * Starts a file transfer with metadata correlation and event emission options.
     *
     * <p>Supports configurable event emission modes for multi-file transfers.
     * When {@code options} specifies a batch completion mode, the framework tracks
     * individual file completions and emits a single batch-completion event when all
     * files have been processed.</p>
     *
     * @param request  the StartFileTransfer SDK request
     * @param metadata the business metadata JSON string to correlate. Must be a valid JSON object
     *                 (not array, not primitive, not {@code null}), maximum 8,000 bytes UTF-8 encoded,
     *                 maximum nesting depth of 50 levels.
     * @param options  event emission options, or {@code null} for default behavior
     * @return the SDK response from the Transfer Family operation
     * @throws IllegalArgumentException if request is null or metadata is invalid
     * @throws MetadataWriteException if the DynamoDB metadata write fails after a successful SDK call
     * @throws software.amazon.awssdk.core.exception.SdkException
     *         if the Transfer Family API call fails (network error, throttling, access denied)
     */
    public StartFileTransferResponse startFileTransfer(StartFileTransferRequest request, String metadata, FileTransferOptions options) {
        if (request == null) {
            throw new IllegalArgumentException("StartFileTransferRequest must not be null");
        }
        validateMetadata(metadata);
        StartFileTransferResponse response = transferClient.startFileTransfer(request);

        FileTransferOptions effective = (options != null) ? options : FileTransferOptions.defaults();
        EventEmissionMode mode = effective.emissionMode();
        if (mode == EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY) {
            writeMetadata(response, response.transferId(), metadata, true);
            return response;
        }

        // Validate batchTimeout < effective TTL
        Duration batchTimeout = effective.batchTimeout();
        if (!batchTimeout.isZero()) {
            long effectiveTtlSeconds = ttlDuration.toSeconds() + 3600;
            if (batchTimeout.toSeconds() >= effectiveTtlSeconds) {
                throw new IllegalArgumentException(
                        "batchTimeout (" + batchTimeout + ") must be less than the metadata record lifetime ("
                        + Duration.ofSeconds(effectiveTtlSeconds) + "). "
                        + "Reduce batchTimeout or increase ttlDuration.");
            }
        }

        writeMetadataWithBatchFields(response, response.transferId(), metadata, request, mode, batchTimeout);
        return response;
    }

    /**
     * Starts a directory listing with metadata correlation.
     *
     * @param request  the StartDirectoryListing SDK request
     * @param metadata the business metadata JSON string to correlate. Must be a valid JSON object
     *                 (not array, not primitive, not {@code null}), maximum 8,000 bytes UTF-8 encoded,
     *                 maximum nesting depth of 50 levels.
     * @return the SDK response from the Transfer Family operation
     * @throws IllegalArgumentException if request is null or metadata is invalid
     * @throws MetadataWriteException if the DynamoDB metadata write fails after a successful SDK call
     * @throws software.amazon.awssdk.core.exception.SdkException
     *         if the Transfer Family API call fails (network error, throttling, access denied)
     */
    public StartDirectoryListingResponse startDirectoryListing(StartDirectoryListingRequest request, String metadata) {
        if (request == null) {
            throw new IllegalArgumentException("StartDirectoryListingRequest must not be null");
        }
        validateMetadata(metadata);
        StartDirectoryListingResponse response = transferClient.startDirectoryListing(request);
        writeMetadata(response, response.listingId(), metadata, false);
        return response;
    }

    /**
     * Starts a remote move with metadata correlation.
     *
     * @param request  the StartRemoteMove SDK request
     * @param metadata the business metadata JSON string to correlate. Must be a valid JSON object
     *                 (not array, not primitive, not {@code null}), maximum 8,000 bytes UTF-8 encoded,
     *                 maximum nesting depth of 50 levels.
     * @return the SDK response from the Transfer Family operation
     * @throws IllegalArgumentException if request is null or metadata is invalid
     * @throws MetadataWriteException if the DynamoDB metadata write fails after a successful SDK call
     * @throws software.amazon.awssdk.core.exception.SdkException
     *         if the Transfer Family API call fails (network error, throttling, access denied)
     */
    public StartRemoteMoveResponse startRemoteMove(StartRemoteMoveRequest request, String metadata) {
        if (request == null) {
            throw new IllegalArgumentException("StartRemoteMoveRequest must not be null");
        }
        validateMetadata(metadata);
        StartRemoteMoveResponse response = transferClient.startRemoteMove(request);
        writeMetadata(response, response.moveId(), metadata, false);
        return response;
    }

    /**
     * Starts a remote delete with metadata correlation.
     *
     * @param request  the StartRemoteDelete SDK request
     * @param metadata the business metadata JSON string to correlate. Must be a valid JSON object
     *                 (not array, not primitive, not {@code null}), maximum 8,000 bytes UTF-8 encoded,
     *                 maximum nesting depth of 50 levels.
     * @return the SDK response from the Transfer Family operation
     * @throws IllegalArgumentException if request is null or metadata is invalid
     * @throws MetadataWriteException if the DynamoDB metadata write fails after a successful SDK call
     * @throws software.amazon.awssdk.core.exception.SdkException
     *         if the Transfer Family API call fails (network error, throttling, access denied)
     */
    public StartRemoteDeleteResponse startRemoteDelete(StartRemoteDeleteRequest request, String metadata) {
        if (request == null) {
            throw new IllegalArgumentException("StartRemoteDeleteRequest must not be null");
        }
        validateMetadata(metadata);
        StartRemoteDeleteResponse response = transferClient.startRemoteDelete(request);
        writeMetadata(response, response.deleteId(), metadata, false);
        return response;
    }

    private void writeMetadata(TransferResponse response, String jobId, String metadata, boolean staggerTtl) {
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
        } catch (ConditionalCheckFailedException e) {
            throw new MetadataWriteException(
                    "Unexpected duplicate metadata for jobId: " + jobId, jobId, response, e);
        } catch (DynamoDbException e) {
            throw new MetadataWriteException(
                    "Failed to write metadata for jobId: " + jobId, jobId, response, e);
        }
    }

    private void writeMetadataWithBatchFields(
            StartFileTransferResponse response, String jobId, String metadata,
            StartFileTransferRequest request, EventEmissionMode mode, Duration batchTimeout) {
        if (jobId == null || jobId.isEmpty()) {
            throw new IllegalStateException("SDK response returned null or empty job ID");
        }
        long ttlEpochSeconds = Instant.now().getEpochSecond() + ttlDuration.toSeconds() + 3600;

        boolean isSend = request.hasSendFilePaths() && !request.sendFilePaths().isEmpty();
        String transferDirection = isSend ? "SEND" : "RETRIEVE";
        java.util.List<String> filePaths = isSend ? request.sendFilePaths() : request.retrieveFilePaths();
        int expectedFiles = filePaths.size();

        // Build update expression and attribute values
        StringBuilder updateExpr = new StringBuilder(
                "SET metadata = :m, #t = :t, emissionMode = :em, expectedFiles = :ef, transferDirection = :td, connectorId = :cid, fileStatuses = :fs");
        java.util.Map<String, String> exprNames = new java.util.HashMap<>();
        exprNames.put("#t", "ttl");
        java.util.Map<String, AttributeValue> exprValues = new java.util.HashMap<>();
        exprValues.put(":m", AttributeValue.builder().s(metadata).build());
        exprValues.put(":t", AttributeValue.builder().n(String.valueOf(ttlEpochSeconds)).build());
        exprValues.put(":em", AttributeValue.builder().s(mode.name()).build());
        exprValues.put(":ef", AttributeValue.builder().n(String.valueOf(expectedFiles)).build());
        exprValues.put(":td", AttributeValue.builder().s(transferDirection).build());
        exprValues.put(":cid", AttributeValue.builder().s(request.connectorId()).build());
        exprValues.put(":fs", AttributeValue.builder().m(Map.of(
                "_init", AttributeValue.builder().m(Map.of()).build()
        )).build());

        // Add batchTimeoutAt and filePaths when timeout is enabled
        if (!batchTimeout.isZero()) {
            long batchTimeoutAt = Instant.now().getEpochSecond() + batchTimeout.getSeconds();
            updateExpr.append(", batchTimeoutAt = :bta, filePaths = :fp");
            exprValues.put(":bta", AttributeValue.builder().n(String.valueOf(batchTimeoutAt)).build());
            exprValues.put(":fp", AttributeValue.builder().l(
                    filePaths.stream()
                            .map(p -> AttributeValue.builder().s(p).build())
                            .toList()
            ).build());
        }

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", AttributeValue.builder().s(jobId).build()))
                .updateExpression(updateExpr.toString())
                .expressionAttributeNames(exprNames)
                .expressionAttributeValues(exprValues)
                .conditionExpression("attribute_not_exists(metadata)")
                .build();

        try {
            dynamoDbClient.updateItem(updateRequest);
        } catch (ConditionalCheckFailedException e) {
            throw new MetadataWriteException(
                    "Unexpected duplicate metadata for jobId: " + jobId, jobId, response, e);
        } catch (DynamoDbException e) {
            throw new MetadataWriteException(
                    "Failed to write metadata for jobId: " + jobId, jobId, response, e);
        }
    }

    /**
     * Validates the metadata JSON string against size and format constraints.
     *
     * @param metadata the metadata JSON string to validate.
     * @throws IllegalArgumentException if metadata is invalid
     */
    void validateMetadata(String metadata) {
        MetadataValidator.validate(metadata);
    }
}
