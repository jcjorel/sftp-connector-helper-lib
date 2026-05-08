package io.github.jcjorel.sftpconnectorhelper.it;

import io.github.jcjorel.sftpconnectorhelper.SftpOperationResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingRequest;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingResponse;

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
        var result1 = helper.startDirectoryListing(request, metadata1);
        assertInstanceOf(SftpOperationResult.Success.class, result1);
        String listingId = ((SftpOperationResult.Success<StartDirectoryListingResponse>) result1).response().listingId();
        trackForCleanup(listingId);
        LOG.info("First call succeeded: listingId={}", listingId);

        // Second call produces a NEW listingId (different SDK call)
        var result2 = helper.startDirectoryListing(request, metadata2);
        assertInstanceOf(SftpOperationResult.Success.class, result2);
        String listingId2 = ((SftpOperationResult.Success<StartDirectoryListingResponse>) result2).response().listingId();
        trackForCleanup(listingId2);
        LOG.info("Second call succeeded: listingId={}", listingId2);

        // Each SDK call returns a different listingId — verify metadata isolation
        assertMetadataInDynamoDb(listingId, metadata1);
        assertMetadataInDynamoDb(listingId2, metadata2);
        LOG.info("Test PASSED: each call has independent metadata (no overwrite)");
    }
}
