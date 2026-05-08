package io.github.jcjorel.sftpconnectorhelper.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jcjorel.sftpconnectorhelper.SftpOperationResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferResponse;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for startFileTransfer (send mode) against a live SFTP Connector.
 */
class StartFileTransferIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(StartFileTransferIT.class);
    private static String testFileKey;

    @BeforeAll
    static void uploadTestFile() {
        testFileKey = "file-transfer-tests/it-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
        LOG.info("Uploading test file to S3: s3://{}/{}", TEST_S3_BUCKET, testFileKey);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(testFileKey).build(),
                RequestBody.fromString("integration-test-content"));
    }

    @Test
    void sendFileSucceedsAndProducesEnrichedEvent() throws Exception {
        String metadata = testMetadata("startFileTransfer");
        LOG.info("Starting file transfer test. File=/{}/{}, remoteDir={}", TEST_S3_BUCKET, testFileKey, REMOTE_DIR);

        StartFileTransferRequest request = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths("/" + TEST_S3_BUCKET + "/" + testFileKey)
                .remoteDirectoryPath(REMOTE_DIR)
                .build();

        var result = helper.startFileTransfer(request, metadata);

        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartFileTransferResponse>) result;
        String transferId = success.response().transferId();
        assertNotNull(transferId);
        assertFalse(transferId.isBlank());
        LOG.info("File transfer started. transferId={}", transferId);

        trackForCleanup(transferId);

        // Verify metadata in DynamoDB
        assertMetadataInDynamoDb(transferId, metadata);

        // Wait for enriched event (file transfer events use "transfer-id")
        JsonNode detail = pollForEnrichedEvent("transfer-id", transferId, Duration.ofSeconds(90));
        assertEnrichedMetadata(detail, metadata);
        LOG.info("Test PASSED: file transfer correlation verified");

        // Cleanup S3
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(TEST_S3_BUCKET).key(testFileKey).build());
        } catch (Exception ignored) {}
    }
}
