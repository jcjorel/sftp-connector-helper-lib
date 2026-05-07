package io.github.jcjorel.sftpconnectorhelper;

import io.github.jcjorel.sftpconnectorhelper.internal.MetadataValidator;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.transfer.TransferClient;

import java.time.Duration;

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
     * Validates metadata before SDK calls.
     * Package-private — called by wrapper methods in Stories 1.3/1.4.
     */
    void validateMetadata(String metadata) {
        MetadataValidator.validate(metadata);
    }
}
