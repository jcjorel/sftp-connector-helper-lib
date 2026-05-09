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
import software.amazon.awssdk.services.transfer.model.StartRemoteMoveRequest;
import software.amazon.awssdk.services.transfer.model.StartRemoteMoveResponse;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for startRemoteMove against a live SFTP Connector.
 * Sends a file to the remote, then moves it to a new path.
 */
class StartRemoteMoveIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(StartRemoteMoveIT.class);
    private static final String FILE_NAME = "it-move-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
    private static String testFileKey;

    @BeforeAll
    static void uploadAndSendFile() {
        // Upload file to S3
        testFileKey = "file-transfer-tests/" + FILE_NAME;
        LOG.info("Uploading test file for move: s3://{}/{}", TEST_S3_BUCKET, testFileKey);
        s3Client.putObject(
                PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(testFileKey).build(),
                RequestBody.fromString("move-test-content"));

        // Send to remote via connector (need file on remote for move)
        LOG.info("Sending file to remote: {}/{}", REMOTE_DIR, FILE_NAME);
        StartFileTransferRequest sendReq = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths("/" + TEST_S3_BUCKET + "/" + testFileKey)
                .remoteDirectoryPath(REMOTE_DIR)
                .build();
        var sendResult = helper.startFileTransfer(sendReq, "{\"setup\":\"move-test\"}");
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
    void moveSucceedsAndProducesEnrichedEvent() throws Exception {
        String metadata = testMetadata("startRemoteMove");
        String sourcePath = REMOTE_DIR + "/" + FILE_NAME;
        String targetPath = REMOTE_DIR + "/" + FILE_NAME + ".moved";
        LOG.info("Starting remote move: {} -> {}", sourcePath, targetPath);

        StartRemoteMoveRequest request = StartRemoteMoveRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sourcePath(sourcePath)
                .targetPath(targetPath)
                .build();

        var result = helper.startRemoteMove(request, metadata);

        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartRemoteMoveResponse>) result;
        String moveId = success.response().moveId();
        assertNotNull(moveId);
        assertFalse(moveId.isBlank());
        LOG.info("Remote move started. moveId={}", moveId);

        trackForCleanup(moveId);

        // Verify metadata in DynamoDB
        assertMetadataInDynamoDb(moveId, metadata);

        // Wait for enriched event
        JsonNode detail = pollForEnrichedEvent("move-id", moveId, Duration.ofSeconds(60));
        assertEnrichedMetadata(detail, metadata);
        LOG.info("Test PASSED: remote move correlation verified");
    }
}
