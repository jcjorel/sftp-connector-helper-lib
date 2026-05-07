package io.github.jcjorel.sftpconnectorhelper;

import io.github.jcjorel.sftpconnectorhelper.internal.MetadataValidator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.transfer.TransferClient;
import software.amazon.awssdk.services.transfer.model.StartFileTransferRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Helper library for AWS Transfer Family SFTP Connector operations
 * with automatic metadata correlation via DynamoDB.
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

    public static SftpConnectorHelperBuilder builder() {
        return new SftpConnectorHelperBuilder();
    }

    public String getTableName() {
        return tableName;
    }

    public Duration getTtlDuration() {
        return ttlDuration;
    }

    public DynamoDbClient getDynamoDbClient() {
        return dynamoDbClient;
    }

    public TransferClient getTransferClient() {
        return transferClient;
    }

    @Override
    public void close() {
        dynamoDbClient.close();
        transferClient.close();
    }

    /**
     * Starts a file transfer with metadata correlation.
     * Validates metadata, executes the SDK call, then writes metadata to DynamoDB.
     *
     * @param request  the StartFileTransfer SDK request
     * @param metadata the business metadata JSON string to correlate
     * @return the operation result indicating success or specific failure mode
     * @throws IllegalArgumentException if metadata is invalid
     */
    public SftpOperationResult<StartFileTransferResponse> startFileTransfer(StartFileTransferRequest request, String metadata) {
        if (request == null) {
            throw new IllegalArgumentException("StartFileTransferRequest must not be null");
        }
        validateMetadata(metadata);
        StartFileTransferResponse response = transferClient.startFileTransfer(request);
        String transferId = response.transferId();
        long ttlEpochSeconds = Instant.now().getEpochSecond() + ttlDuration.toSeconds() + 3600;

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", AttributeValue.builder().s(transferId).build()))
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
            return new SftpOperationResult.MetadataAlreadyExists<>(response, transferId);
        } catch (DynamoDbException e) {
            return new SftpOperationResult.MetadataWriteFailed<>(response, transferId, e);
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
