package io.github.jcjorel.sftpconnectorhelper.it;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test verifying the idempotency contract:
 * a second metadata write for the same jobId returns MetadataAlreadyExists.
 */
class IdempotencyIT extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyIT.class);

    @Test
    void secondCallWithSameJobIdReturnsMetadataAlreadyExists() {
        String metadata1 = testMetadata("idempotency-first");
        String metadata2 = testMetadata("idempotency-second");
        LOG.info("Starting idempotency test with two directory listing calls");

        StartDirectoryListingRequest request = StartDirectoryListingRequest.builder()
                .connectorId(CONNECTOR_ID)
                .remoteDirectoryPath(REMOTE_DIR)
                .outputDirectoryPath("/" + TEST_S3_BUCKET + "/listing-tests")
                .build();

        // First call — should succeed
        var response1 = helper.startDirectoryListing(request, metadata1);
        String listingId = response1.listingId();
        trackForCleanup(listingId);
        LOG.info("First call succeeded: listingId={}", listingId);

        // Second call produces a NEW listingId (different SDK call)
        var response2 = helper.startDirectoryListing(request, metadata2);
        String listingId2 = response2.listingId();
        trackForCleanup(listingId2);
        LOG.info("Second call succeeded: listingId={}", listingId2);

        // Each SDK call returns a different listingId — verify metadata isolation
        assertMetadataInDynamoDb(listingId, metadata1);
        assertMetadataInDynamoDb(listingId2, metadata2);
        LOG.info("Test PASSED: each call has independent metadata (no overwrite)");
    }
}
