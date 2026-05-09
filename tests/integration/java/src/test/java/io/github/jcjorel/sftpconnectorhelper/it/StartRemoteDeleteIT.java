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
import software.amazon.awssdk.services.transfer.model.StartRemoteDeleteRequest;
import software.amazon.awssdk.services.transfer.model.StartRemoteDeleteResponse;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for startRemoteDelete against a live SFTP Connector.
 * Sends a file to the remote, then deletes it.
 */
class StartRemoteDeleteIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(StartRemoteDeleteIT.class);
    private static final String FILE_NAME = "it-delete-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
    private static String testFileKey;

    @BeforeAll
    static void uploadAndSendFile() {
        // Upload file to S3
        testFileKey = "file-transfer-tests/" + FILE_NAME;
        LOG.info("Uploading test file for delete: s3://{}/{}", TEST_S3_BUCKET, testFileKey);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(testFileKey).build(),
                RequestBody.fromString("delete-test-content"));

        // Send to remote via connector
        LOG.info("Sending file to remote: {}/{}", REMOTE_DIR, FILE_NAME);
        StartFileTransferRequest sendReq = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths("/" + TEST_S3_BUCKET + "/" + testFileKey)
                .remoteDirectoryPath(REMOTE_DIR)
                .build();
        var sendResult = helper.startFileTransfer(sendReq, "{\"setup\":\"delete-test\"}");
        assertInstanceOf(SftpOperationResult.Success.class, sendResult);
        String transferId = ((SftpOperationResult.Success<StartFileTransferResponse>) sendResult).response().transferId();
        jobIdsToCleanup.add(transferId);
        LOG.info("Setup transfer started: transferId={}. Waiting for completion via event polling...", transferId);

        // Wait for transfer to complete using event-based polling (not Thread.sleep)
        pollForRawEvent("transfer-id", transferId, Duration.ofSeconds(90));

        // Cleanup S3 test file
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(TEST_S3_BUCKET).key(testFileKey).build());
    }

    @Test
    void deleteSucceedsAndProducesEnrichedEvent() throws Exception {
        String metadata = testMetadata("startRemoteDelete");
        String deletePath = REMOTE_DIR + "/" + FILE_NAME;
        LOG.info("Starting remote delete: {}", deletePath);

        StartRemoteDeleteRequest request = StartRemoteDeleteRequest.builder()
                .connectorId(CONNECTOR_ID)
                .deletePath(deletePath)
                .build();

        var result = helper.startRemoteDelete(request, metadata);

        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartRemoteDeleteResponse>) result;
        String deleteId = success.response().deleteId();
        assertNotNull(deleteId);
        assertFalse(deleteId.isBlank());
        LOG.info("Remote delete started. deleteId={}", deleteId);

        trackForCleanup(deleteId);

        // Verify metadata in DynamoDB
        assertMetadataInDynamoDb(deleteId, metadata);

        // Wait for enriched event
        JsonNode detail = pollForEnrichedEvent("delete-id", deleteId, Duration.ofSeconds(60));
        assertEnrichedMetadata(detail, metadata);
        LOG.info("Test PASSED: remote delete correlation verified");
    }
}
