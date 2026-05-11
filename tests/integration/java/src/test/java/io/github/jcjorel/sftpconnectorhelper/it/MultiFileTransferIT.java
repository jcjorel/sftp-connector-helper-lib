package io.github.jcjorel.sftpconnectorhelper.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jcjorel.sftpconnectorhelper.EventEmissionMode;
import io.github.jcjorel.sftpconnectorhelper.FileTransferOptions;
import io.github.jcjorel.sftpconnectorhelper.SftpOperationResult;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for multi-file transfers (10 files in a single API call).
 * Validates the fan-out correlation: one transferId → N per-file enriched events.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiFileTransferIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(MultiFileTransferIT.class);
    private static final int FILE_COUNT = 10;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(180);
    private static final String SEND_BATCH_DETAIL_TYPE = "SFTP Connector Whole File Send Transfer Completed - CUSTOM";
    private static final String RETRIEVE_BATCH_DETAIL_TYPE = "SFTP Connector Whole File Retrieve Transfer Completed - CUSTOM";
    private static final String INDIVIDUAL_SEND_DETAIL_TYPE = "SFTP Connector File Send Completed";
    private static final String INDIVIDUAL_RETRIEVE_DETAIL_TYPE = "SFTP Connector File Retrieve Completed";

    @Test
    @Order(1)
    void multiFileSendProducesAllEnrichedEvents() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<String> s3Keys = new ArrayList<>();
        List<String> sendPaths = new ArrayList<>();

        // Upload 10 files to S3
        for (int i = 0; i < FILE_COUNT; i++) {
            String key = "multi-send/" + uid + "/file-" + i + ".txt";
            s3Keys.add(key);
            sendPaths.add("/" + TEST_S3_BUCKET + "/" + key);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build(),
                    RequestBody.fromString("multi-file-send-content-" + i));
        }
        LOG.info("Uploaded {} files for multi-file send test (uid={})", FILE_COUNT, uid);

        // Single API call with 10 sendFilePaths
        String metadata = testMetadata("multiFileSend");
        StartFileTransferRequest request = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths(sendPaths)
                .remoteDirectoryPath(REMOTE_DIR)
                .build();

        var result = helper.startFileTransfer(request, metadata);
        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartFileTransferResponse>) result;
        String transferId = success.response().transferId();
        assertNotNull(transferId);
        LOG.info("Multi-file send started. transferId={}", transferId);
        trackForCleanup(transferId);

        // Verify metadata in DynamoDB (master record)
        assertMetadataInDynamoDb(transferId, metadata);

        // Wait for all 10 per-file enriched events
        long startTime = System.currentTimeMillis();
        List<JsonNode> events = pollForAllEnrichedEvents("transfer-id", transferId, FILE_COUNT, POLL_TIMEOUT);
        long elapsed = System.currentTimeMillis() - startTime;
        LOG.info("All {} send events received in {}ms (avg {}ms/file)", FILE_COUNT, elapsed, elapsed / FILE_COUNT);
        assertEquals(FILE_COUNT, events.size(), "Expected " + FILE_COUNT + " enriched events");

        // Verify uniqueness: no duplicate file-transfer-ids
        Set<String> fileTransferIds = events.stream()
                .map(e -> e.get("file-transfer-id").asText())
                .collect(Collectors.toSet());
        assertEquals(FILE_COUNT, fileTransferIds.size(), "All per-file events must have unique file-transfer-ids");

        // Verify completeness: each expected file path appears
        Set<String> filePaths = events.stream()
                .map(e -> e.get("file-path").asText())
                .collect(Collectors.toSet());
        for (int i = 0; i < FILE_COUNT; i++) {
            int idx = i;
            assertTrue(filePaths.stream().anyMatch(p -> p.contains("file-" + idx + ".txt")),
                    "Missing enriched event for file-" + i);
        }

        // Verify each event has correct metadata and COMPLETED status
        for (JsonNode detail : events) {
            assertEnrichedMetadata(detail, metadata);
            assertTrue(detail.has("file-transfer-id"), "Per-file event must have file-transfer-id");
            assertEquals("COMPLETED", detail.get("status-code").asText(),
                    "File " + detail.get("file-path").asText() + " did not complete successfully");
        }
        LOG.info("Test PASSED: all {} per-file enriched events received with correct metadata", FILE_COUNT);

        // Cleanup S3
        for (String key : s3Keys) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(2)
    void multiFileRetrieveProducesAllEnrichedEvents() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<String> s3Keys = new ArrayList<>();
        List<String> remotePaths = new ArrayList<>();

        // Setup: send 10 files to the remote server first
        List<String> setupSendPaths = new ArrayList<>();
        for (int i = 0; i < FILE_COUNT; i++) {
            String key = "multi-retrieve-setup/" + uid + "/file-" + i + ".txt";
            s3Keys.add(key);
            setupSendPaths.add("/" + TEST_S3_BUCKET + "/" + key);
            remotePaths.add(REMOTE_DIR + "/file-" + i + ".txt");
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build(),
                    RequestBody.fromString("multi-file-retrieve-content-" + i));
        }
        LOG.info("Uploading {} files to remote server for retrieve test setup (uid={})", FILE_COUNT, uid);

        StartFileTransferRequest setupReq = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths(setupSendPaths)
                .remoteDirectoryPath(REMOTE_DIR)
                .build();
        StartFileTransferResponse setupResp = transferClient.startFileTransfer(setupReq);
        String setupTransferId = setupResp.transferId();
        trackForCleanup(setupTransferId);
        LOG.info("Setup send started. transferId={}, waiting for {} completions...", setupTransferId, FILE_COUNT);

        // Wait for all setup files to arrive on remote (poll raw events)
        pollForAllRawEvents("transfer-id", setupTransferId, FILE_COUNT, POLL_TIMEOUT);
        LOG.info("All {} setup files delivered to remote server", FILE_COUNT);

        // Now retrieve the 10 files back via helper
        String metadata = testMetadata("multiFileRetrieve");
        String localDir = "/" + TEST_S3_BUCKET + "/multi-retrieve-result/" + uid;
        StartFileTransferRequest retrieveReq = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .retrieveFilePaths(remotePaths)
                .localDirectoryPath(localDir)
                .build();

        var result = helper.startFileTransfer(retrieveReq, metadata);
        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartFileTransferResponse>) result;
        String transferId = success.response().transferId();
        assertNotNull(transferId);
        LOG.info("Multi-file retrieve started. transferId={}", transferId);
        trackForCleanup(transferId);

        // Verify metadata in DynamoDB (master record)
        assertMetadataInDynamoDb(transferId, metadata);

        // Wait for all 10 per-file enriched events
        long startTime = System.currentTimeMillis();
        List<JsonNode> events = pollForAllEnrichedEvents("transfer-id", transferId, FILE_COUNT, POLL_TIMEOUT);
        long elapsed = System.currentTimeMillis() - startTime;
        LOG.info("All {} retrieve events received in {}ms (avg {}ms/file)", FILE_COUNT, elapsed, elapsed / FILE_COUNT);
        assertEquals(FILE_COUNT, events.size(), "Expected " + FILE_COUNT + " enriched events");

        // Verify uniqueness: no duplicate file-transfer-ids
        Set<String> fileTransferIds = events.stream()
                .map(e -> e.get("file-transfer-id").asText())
                .collect(Collectors.toSet());
        assertEquals(FILE_COUNT, fileTransferIds.size(), "All per-file events must have unique file-transfer-ids");

        // Verify completeness: each expected file path appears
        Set<String> filePaths = events.stream()
                .map(e -> e.get("file-path").asText())
                .collect(Collectors.toSet());
        for (int i = 0; i < FILE_COUNT; i++) {
            int idx = i;
            assertTrue(filePaths.stream().anyMatch(p -> p.contains("file-" + idx + ".txt")),
                    "Missing enriched event for file-" + i);
        }

        // Verify each event has correct metadata and COMPLETED status
        for (JsonNode detail : events) {
            assertEnrichedMetadata(detail, metadata);
            assertTrue(detail.has("file-transfer-id"), "Per-file event must have file-transfer-id");
            assertEquals("COMPLETED", detail.get("status-code").asText(),
                    "File " + detail.get("file-path").asText() + " did not complete successfully");
        }
        LOG.info("Test PASSED: all {} per-file retrieve events received with correct metadata", FILE_COUNT);

        // Cleanup S3 (setup files + retrieved files)
        for (String key : s3Keys) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build());
            } catch (Exception ignored) {}
        }
        for (int i = 0; i < FILE_COUNT; i++) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(TEST_S3_BUCKET).key("multi-retrieve-result/" + uid + "/file-" + i + ".txt").build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(3)
    void multiFileSendWithWholeTransferCompletionOnly() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<String> s3Keys = new ArrayList<>();
        List<String> sendPaths = new ArrayList<>();

        for (int i = 0; i < FILE_COUNT; i++) {
            String key = "multi-send-batch/" + uid + "/file-" + i + ".txt";
            s3Keys.add(key);
            sendPaths.add("/" + TEST_S3_BUCKET + "/" + key);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build(),
                    RequestBody.fromString("batch-send-content-" + i));
        }
        LOG.info("Uploaded {} files for batch-only send test (uid={})", FILE_COUNT, uid);

        String metadata = testMetadata("multiFileSendBatchOnly");
        FileTransferOptions options = FileTransferOptions.builder()
                .emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY)
                .build();
        StartFileTransferRequest request = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths(sendPaths)
                .remoteDirectoryPath(REMOTE_DIR)
                .build();

        var result = helper.startFileTransfer(request, metadata, options);
        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartFileTransferResponse>) result;
        String transferId = success.response().transferId();
        assertNotNull(transferId);
        LOG.info("Batch-only send started. transferId={}", transferId);
        trackForCleanup(transferId);

        assertMetadataInDynamoDb(transferId, metadata);

        // Wait for the single batch completion event
        List<JsonNode> batchEvents = pollForEnrichedEventsByDetailType(
                SEND_BATCH_DETAIL_TYPE, "transfer-id", transferId, 1, POLL_TIMEOUT);
        assertEquals(1, batchEvents.size(), "Expected exactly 1 batch completion event");

        JsonNode batch = batchEvents.get(0);
        assertEquals("ALL_COMPLETED", batch.get("status-code").asText());
        assertEquals(FILE_COUNT, batch.get("file-count").asInt());
        assertEquals(FILE_COUNT, batch.get("completed-count").asInt());
        assertEquals(0, batch.get("failed-count").asInt());
        assertEnrichedMetadata(batch, metadata);
        assertEquals(FILE_COUNT, batch.get("files").size(), "files array must contain all " + FILE_COUNT + " entries");
        LOG.info("Batch completion event validated: status-code=ALL_COMPLETED, file-count={}", FILE_COUNT);

        // Assert no individual per-file events leaked
        List<JsonNode> individual = pollAndExpectNoEvents(
                INDIVIDUAL_SEND_DETAIL_TYPE, "transfer-id", transferId, Duration.ofSeconds(30));
        assertTrue(individual.isEmpty(),
                "Expected no individual events in WHOLE_TRANSFER_COMPLETION_ONLY mode, got " + individual.size());
        LOG.info("Test PASSED: batch-only send — 1 batch event, 0 individual events");

        for (String key : s3Keys) { cleanupS3(key); }
    }

    @Test
    @Order(4)
    void multiFileRetrieveWithWholeTransferCompletionOnly() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<String> s3Keys = new ArrayList<>();
        List<String> remotePaths = new ArrayList<>();

        // Setup: send files to remote server first
        List<String> setupSendPaths = new ArrayList<>();
        for (int i = 0; i < FILE_COUNT; i++) {
            String key = "multi-retrieve-batch-setup/" + uid + "/file-" + i + ".txt";
            s3Keys.add(key);
            setupSendPaths.add("/" + TEST_S3_BUCKET + "/" + key);
            remotePaths.add(REMOTE_DIR + "/file-" + i + ".txt");
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build(),
                    RequestBody.fromString("batch-retrieve-content-" + i));
        }

        StartFileTransferRequest setupReq = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths(setupSendPaths)
                .remoteDirectoryPath(REMOTE_DIR)
                .build();
        String setupTransferId = transferClient.startFileTransfer(setupReq).transferId();
        trackForCleanup(setupTransferId);
        pollForAllRawEvents("transfer-id", setupTransferId, FILE_COUNT, POLL_TIMEOUT);
        LOG.info("Setup complete: {} files on remote server (uid={})", FILE_COUNT, uid);

        // Retrieve with batch-only mode
        String metadata = testMetadata("multiFileRetrieveBatchOnly");
        FileTransferOptions options = FileTransferOptions.builder()
                .emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY)
                .build();
        String localDir = "/" + TEST_S3_BUCKET + "/multi-retrieve-batch-result/" + uid;
        StartFileTransferRequest retrieveReq = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .retrieveFilePaths(remotePaths)
                .localDirectoryPath(localDir)
                .build();

        var result = helper.startFileTransfer(retrieveReq, metadata, options);
        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartFileTransferResponse>) result;
        String transferId = success.response().transferId();
        assertNotNull(transferId);
        LOG.info("Batch-only retrieve started. transferId={}", transferId);
        trackForCleanup(transferId);

        assertMetadataInDynamoDb(transferId, metadata);

        // Wait for the single batch completion event
        List<JsonNode> batchEvents = pollForEnrichedEventsByDetailType(
                RETRIEVE_BATCH_DETAIL_TYPE, "transfer-id", transferId, 1, POLL_TIMEOUT);
        assertEquals(1, batchEvents.size(), "Expected exactly 1 batch completion event");

        JsonNode batch = batchEvents.get(0);
        assertEquals("ALL_COMPLETED", batch.get("status-code").asText());
        assertEquals(FILE_COUNT, batch.get("file-count").asInt());
        assertEquals(FILE_COUNT, batch.get("completed-count").asInt());
        assertEquals(0, batch.get("failed-count").asInt());
        assertEnrichedMetadata(batch, metadata);
        assertEquals(FILE_COUNT, batch.get("files").size());
        LOG.info("Batch completion event validated for retrieve direction");

        // Assert no individual per-file events leaked
        List<JsonNode> individual = pollAndExpectNoEvents(
                INDIVIDUAL_RETRIEVE_DETAIL_TYPE, "transfer-id", transferId, Duration.ofSeconds(30));
        assertTrue(individual.isEmpty(),
                "Expected no individual events in WHOLE_TRANSFER_COMPLETION_ONLY mode, got " + individual.size());
        LOG.info("Test PASSED: batch-only retrieve — 1 batch event, 0 individual events");

        for (String key : s3Keys) { cleanupS3(key); }
        cleanupS3Prefix("multi-retrieve-batch-result/" + uid);
    }

    @Test
    @Order(5)
    void multiFileSendWithIndividualAndWholeTransferCompletion() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<String> s3Keys = new ArrayList<>();
        List<String> sendPaths = new ArrayList<>();

        for (int i = 0; i < FILE_COUNT; i++) {
            String key = "multi-send-dual/" + uid + "/file-" + i + ".txt";
            s3Keys.add(key);
            sendPaths.add("/" + TEST_S3_BUCKET + "/" + key);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build(),
                    RequestBody.fromString("dual-mode-content-" + i));
        }
        LOG.info("Uploaded {} files for dual-emission send test (uid={})", FILE_COUNT, uid);

        String metadata = testMetadata("multiFileSendDual");
        FileTransferOptions options = FileTransferOptions.builder()
                .emissionMode(EventEmissionMode.INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION)
                .build();
        StartFileTransferRequest request = StartFileTransferRequest.builder()
                .connectorId(CONNECTOR_ID)
                .sendFilePaths(sendPaths)
                .remoteDirectoryPath(REMOTE_DIR)
                .build();

        var result = helper.startFileTransfer(request, metadata, options);
        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartFileTransferResponse>) result;
        String transferId = success.response().transferId();
        assertNotNull(transferId);
        LOG.info("Dual-emission send started. transferId={}", transferId);
        trackForCleanup(transferId);

        assertMetadataInDynamoDb(transferId, metadata);

        // Wait for all N+1 events (10 individual + 1 batch) in a single pass to avoid
        // discarding the batch event while polling for individual events (they share one SQS queue)
        List<JsonNode> allEvents = pollForAllEnrichedEvents("transfer-id", transferId, FILE_COUNT + 1, POLL_TIMEOUT);
        assertEquals(FILE_COUNT + 1, allEvents.size(), "Expected " + (FILE_COUNT + 1) + " total events");

        // Partition by presence of "file-count" field (batch event has it, individual events don't)
        List<JsonNode> individualEvents = new ArrayList<>();
        List<JsonNode> batchEvents = new ArrayList<>();
        for (JsonNode event : allEvents) {
            if (event.has("file-count")) {
                batchEvents.add(event);
            } else {
                individualEvents.add(event);
            }
        }

        assertEquals(FILE_COUNT, individualEvents.size(), "Expected " + FILE_COUNT + " individual events");
        LOG.info("Received all {} individual per-file events", FILE_COUNT);

        assertEquals(1, batchEvents.size(), "Expected exactly 1 batch completion event");
        JsonNode batch = batchEvents.get(0);
        assertEquals("ALL_COMPLETED", batch.get("status-code").asText());
        assertEquals(FILE_COUNT, batch.get("file-count").asInt());
        assertEnrichedMetadata(batch, metadata);
        LOG.info("Test PASSED: dual-emission — {} individual events + 1 batch event", FILE_COUNT);

        for (String key : s3Keys) { cleanupS3(key); }
    }
}
