package io.github.jcjorel.sftpconnectorhelper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.transfer.TransferClient;

import java.time.Duration;

/**
 * Builder for {@link SftpConnectorHelper}.
 */
public final class SftpConnectorHelperBuilder {

    private String tableName;
    private Duration ttlDuration;
    private DynamoDbClient dynamoDbClient;
    private TransferClient transferClient;

    SftpConnectorHelperBuilder() {}

    public SftpConnectorHelperBuilder tableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public SftpConnectorHelperBuilder ttlDuration(Duration ttlDuration) {
        this.ttlDuration = ttlDuration;
        return this;
    }

    public SftpConnectorHelperBuilder dynamoDbClient(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        return this;
    }

    public SftpConnectorHelperBuilder transferClient(TransferClient transferClient) {
        this.transferClient = transferClient;
        return this;
    }

    public SftpConnectorHelper build() {
        String resolvedTableName = tableName != null ? tableName : SftpConnectorHelper.DEFAULT_TABLE_NAME;
        if (resolvedTableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        Duration resolvedTtl = ttlDuration != null ? ttlDuration : SftpConnectorHelper.DEFAULT_TTL_DURATION;
        if (resolvedTtl.isZero() || resolvedTtl.isNegative()) {
            throw new IllegalArgumentException("ttlDuration must be positive");
        }
        DynamoDbClient resolvedDynamo = dynamoDbClient != null ? dynamoDbClient : DynamoDbClient.create();
        TransferClient resolvedTransfer = transferClient != null ? transferClient : TransferClient.create();

        return new SftpConnectorHelper(resolvedTableName, resolvedTtl, resolvedDynamo, resolvedTransfer);
    }
}
