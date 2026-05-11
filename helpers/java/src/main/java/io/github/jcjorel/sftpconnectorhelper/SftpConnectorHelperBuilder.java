package io.github.jcjorel.sftpconnectorhelper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.transfer.TransferClient;

import java.time.Duration;

/**
 * Builder for {@link SftpConnectorHelper}.
 *
 * <p>All parameters are optional. When omitted, sensible defaults are used:
 * <ul>
 *   <li>{@code tableName} — defaults to {@code "sftp-connector-helper"}</li>
 *   <li>{@code ttlDuration} — defaults to {@link SftpConnectorHelper#DEFAULT_TTL_DURATION}</li>
 *   <li>{@code dynamoDbClient} — creates a default client using the standard credential chain</li>
 *   <li>{@code transferClient} — creates a default client using the standard credential chain</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This builder is not thread-safe. Create a new builder instance
 * per thread or synchronize externally.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SftpConnectorHelper helper = SftpConnectorHelper.builder()
 *     .tableName("my-table")
 *     .ttlDuration(Duration.ofHours(12))
 *     .build();
 * }</pre>
 *
 * @see SftpConnectorHelper
 */
public final class SftpConnectorHelperBuilder {

    private String tableName;
    private Duration ttlDuration;
    private DynamoDbClient dynamoDbClient;
    private TransferClient transferClient;

    SftpConnectorHelperBuilder() {}

    /**
     * Sets the DynamoDB table name for metadata storage.
     *
     * @param tableName the table name (default: {@code "sftp-connector-helper"})
     * @return this builder
     */
    public SftpConnectorHelperBuilder tableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    /**
     * Sets the TTL duration for metadata records.
     *
     * @param ttlDuration the TTL duration (default: 24 hours); must be positive if non-null.
     *                    Null uses the default.
     * @return this builder
     */
    public SftpConnectorHelperBuilder ttlDuration(Duration ttlDuration) {
        this.ttlDuration = ttlDuration;
        return this;
    }

    /**
     * Sets a custom DynamoDB client.
     *
     * <p><b>Note:</b> When a custom client is provided, {@link SftpConnectorHelper#close()}
     * will still call {@code close()} on it. If you need the client to outlive the helper,
     * do not pass it here.</p>
     *
     * @param dynamoDbClient the DynamoDB client to use (default: creates a new client)
     * @return this builder
     */
    public SftpConnectorHelperBuilder dynamoDbClient(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        return this;
    }

    /**
     * Sets a custom Transfer Family client.
     *
     * <p><b>Note:</b> When a custom client is provided, {@link SftpConnectorHelper#close()}
     * will still call {@code close()} on it. If you need the client to outlive the helper,
     * do not pass it here.</p>
     *
     * @param transferClient the Transfer client to use (default: creates a new client)
     * @return this builder
     */
    public SftpConnectorHelperBuilder transferClient(TransferClient transferClient) {
        this.transferClient = transferClient;
        return this;
    }

    /**
     * Builds and returns a configured {@link SftpConnectorHelper} instance.
     *
     * @return a new helper instance
     * @throws IllegalArgumentException if tableName is blank or ttlDuration is non-positive
     */
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
