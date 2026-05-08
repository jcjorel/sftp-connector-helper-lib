package io.github.jcjorel.sftpconnectorhelper.it;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jcjorel.sftpconnectorhelper.SftpOperationResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingRequest;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for startDirectoryListing against a live SFTP Connector.
 */
class StartDirectoryListingIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(StartDirectoryListingIT.class);

    @Test
    void listingSucceedsAndProducesEnrichedEvent() throws Exception {
        String metadata = testMetadata("startDirectoryListing");
        LOG.info("Starting directory listing test. Connector={}, remoteDir={}", CONNECTOR_ID, REMOTE_DIR);

        StartDirectoryListingRequest request = StartDirectoryListingRequest.builder()
                .connectorId(CONNECTOR_ID)
                .remoteDirectoryPath(REMOTE_DIR)
                .outputDirectoryPath("/" + TEST_S3_BUCKET + "/listing-tests")
                .build();

        var result = helper.startDirectoryListing(request, metadata);

        assertInstanceOf(SftpOperationResult.Success.class, result);
        var success = (SftpOperationResult.Success<StartDirectoryListingResponse>) result;
        String listingId = success.response().listingId();
        assertNotNull(listingId);
        assertFalse(listingId.isBlank());
        LOG.info("Directory listing started. listingId={}", listingId);

        trackForCleanup(listingId);

        // Verify metadata written to DynamoDB
        assertMetadataInDynamoDb(listingId, metadata);

        // Wait for enriched event
        JsonNode detail = pollForEnrichedEvent("listing-id", listingId, Duration.ofSeconds(60));
        assertEnrichedMetadata(detail, metadata);
        LOG.info("Test PASSED: directory listing correlation verified");
    }
}
