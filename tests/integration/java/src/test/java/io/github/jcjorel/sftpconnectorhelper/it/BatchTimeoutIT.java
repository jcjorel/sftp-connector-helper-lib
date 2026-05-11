package io.github.jcjorel.sftpconnectorhelper.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jcjorel.sftpconnectorhelper.EventEmissionMode;
import io.github.jcjorel.sftpconnectorhelper.FileTransferOptions;
import io.github.jcjorel.sftpconnectorhelper.SftpOperationResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the batch timeout feature.
 *
 * <p>This test uses an intentionally short batch timeout (4 seconds) to exercise the
 * timeout code path. With 10 files sent over SFTP, the transfer typically takes 5–15 seconds,
 * so the 4-second timeout fires before all files complete, producing a {@code TIMED_OUT} event.</p>
 *
 * <p><b>Why the minimum was lowered to 2 seconds:</b> The library's minimum batch timeout was
 * relaxed from 15 minutes to 2 seconds solely to enable this integration test. Testing the
 * timeout path with a 15-minute minimum would make the test suite impractically slow.</p>
 *
 * <p><b>Production guidance:</b> Never use batch timeouts below 15 minutes in production.
 * SFTP Connector transfers have variable latency, and the timeout mechanism relies on SQS
 * delayed messages with 1-second granularity. Short timeouts will produce spurious timeout
 * events for transfers that would otherwise complete successfully.</p>
 */
class BatchTimeoutIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(BatchTimeoutIT.class);
    private static final int FILE_COUNT = 10;
    private static final Duration BATCH_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    private static final String SEND_TIMEOUT_DETAIL_TYPE = "SFTP Connector Whole File Send Transfer Timed Out - CUSTOM";

    @Test
    void multiFileSendTimesOutAndProducesTimeoutEvent() throws Exception {
        String uid = UUID.randomUUID().toString().substring(0, 8);
        List<String> s3Keys = new ArrayList<>();
        List<String> sendPaths = new ArrayList<>();

        for (int i = 0; i < FILE_COUNT; i++) {
            String key = "batch-timeout/" + uid + "/file-" + i + ".txt";
            s3Keys.add(key);
            sendPaths.add("/" + TEST_S3_BUCKET + "/" + key);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(TEST_S3_BUCKET).key(key).build(),
                    RequestBody.fromString("timeout-test-content-" + i));
        }
        LOG.info("Uploaded {} files for batch timeout test (uid={}, timeout={}s)", FILE_COUNT, uid, BATCH_TIMEOUT.toSeconds());

        String metadata = testMetadata("batchTimeout");
        FileTransferOptions options = FileTransferOptions.builder()
                .emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY)
                .batchTimeout(BATCH_TIMEOUT)
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
        LOG.info("Batch timeout send started. transferId={}", transferId);
        trackForCleanup(transferId);

        assertMetadataInDynamoDb(transferId, metadata);

        // Wait for the timeout event
        List<JsonNode> timeoutEvents = pollForEnrichedEventsByDetailType(
                SEND_TIMEOUT_DETAIL_TYPE, "transfer-id", transferId, 1, POLL_TIMEOUT);
        assertEquals(1, timeoutEvents.size(), "Expected exactly 1 batch timeout event");

        JsonNode timeout = timeoutEvents.get(0);
        assertEquals("TIMED_OUT", timeout.get("status-code").asText());
        assertEquals(FILE_COUNT, timeout.get("file-count").asInt());
        assertTrue(timeout.get("timed-out-count").asInt() > 0,
                "Expected at least one file to have timed out");
        int completed = timeout.get("completed-count").asInt();
        int failed = timeout.get("failed-count").asInt();
        int timedOut = timeout.get("timed-out-count").asInt();
        assertEquals(FILE_COUNT, completed + failed + timedOut,
                "completed + failed + timed-out must equal file-count");
        assertEnrichedMetadata(timeout, metadata);
        assertEquals(FILE_COUNT, timeout.get("files").size(), "files array must contain all " + FILE_COUNT + " entries");
        LOG.info("Test PASSED: batch timeout event received — completed={}, failed={}, timed-out={}",
                completed, failed, timedOut);

        for (String key : s3Keys) { cleanupS3(key); }
    }
}
