package io.github.jcjorel.sftpconnectorhelper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.transfer.TransferClient;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingRequest;
import software.amazon.awssdk.services.transfer.model.StartDirectoryListingResponse;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SftpConnectorHelperDirectoryListingTest {

    private static final String TABLE_NAME = "test-table";
    private static final Duration TTL_DURATION = Duration.ofHours(24);
    private static final String LISTING_ID = "l-abc123";
    private static final String METADATA = "{\"key\":\"value\"}";

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private TransferClient transferClient;

    private SftpConnectorHelper helper;
    private StartDirectoryListingRequest sdkRequest;

    @BeforeEach
    void setUp() {
        helper = new SftpConnectorHelper(TABLE_NAME, TTL_DURATION, dynamoDbClient, transferClient);
        sdkRequest = StartDirectoryListingRequest.builder()
                .connectorId("c-123")
                .remoteDirectoryPath("/remote/dir")
                .outputDirectoryPath("/output")
                .build();
    }

    @Test
    void startDirectoryListing_success_returnsSuccess() {
        StartDirectoryListingResponse response = StartDirectoryListingResponse.builder()
                .listingId(LISTING_ID).build();
        when(transferClient.startDirectoryListing(sdkRequest)).thenReturn(response);

        SftpOperationResult<StartDirectoryListingResponse> result = helper.startDirectoryListing(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.Success.class, result);
        assertEquals(response, ((SftpOperationResult.Success<StartDirectoryListingResponse>) result).response());
    }

    @Test
    void startDirectoryListing_sdkThrows_propagatesNoDynamo() {
        RuntimeException ex = new RuntimeException("SDK failure");
        when(transferClient.startDirectoryListing(sdkRequest)).thenThrow(ex);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> helper.startDirectoryListing(sdkRequest, METADATA));

        assertSame(ex, thrown);
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void startDirectoryListing_conditionalCheckFails_returnsMetadataAlreadyExists() {
        StartDirectoryListingResponse response = StartDirectoryListingResponse.builder()
                .listingId(LISTING_ID).build();
        when(transferClient.startDirectoryListing(sdkRequest)).thenReturn(response);
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("exists").build());

        SftpOperationResult<StartDirectoryListingResponse> result = helper.startDirectoryListing(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.MetadataAlreadyExists.class, result);
        var r = (SftpOperationResult.MetadataAlreadyExists<StartDirectoryListingResponse>) result;
        assertEquals(response, r.response());
        assertEquals(LISTING_ID, r.jobId());
    }

    @Test
    void startDirectoryListing_dynamoThrows_returnsMetadataWriteFailed() {
        StartDirectoryListingResponse response = StartDirectoryListingResponse.builder()
                .listingId(LISTING_ID).build();
        when(transferClient.startDirectoryListing(sdkRequest)).thenReturn(response);
        DynamoDbException dynamoEx = (DynamoDbException) DynamoDbException.builder().message("err").build();
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class))).thenThrow(dynamoEx);

        SftpOperationResult<StartDirectoryListingResponse> result = helper.startDirectoryListing(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.MetadataWriteFailed.class, result);
        var r = (SftpOperationResult.MetadataWriteFailed<StartDirectoryListingResponse>) result;
        assertEquals(response, r.response());
        assertEquals(LISTING_ID, r.jobId());
        assertSame(dynamoEx, r.cause());
    }

    @Test
    void startDirectoryListing_invalidMetadata_throwsNoSdkCall() {
        assertThrows(IllegalArgumentException.class,
                () -> helper.startDirectoryListing(sdkRequest, "not-json"));

        verify(transferClient, never()).startDirectoryListing(any(StartDirectoryListingRequest.class));
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void startDirectoryListing_nullRequest_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> helper.startDirectoryListing(null, METADATA));
    }

    @Test
    void startDirectoryListing_ttlHasNoStagger() {
        StartDirectoryListingResponse response = StartDirectoryListingResponse.builder()
                .listingId(LISTING_ID).build();
        when(transferClient.startDirectoryListing(sdkRequest)).thenReturn(response);

        long beforeCall = Instant.now().getEpochSecond() + TTL_DURATION.toSeconds();
        helper.startDirectoryListing(sdkRequest, METADATA);
        long afterCall = Instant.now().getEpochSecond() + TTL_DURATION.toSeconds();

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());
        long actualTtl = Long.parseLong(captor.getValue().expressionAttributeValues().get(":t").n());

        assertTrue(actualTtl >= beforeCall - 2 && actualTtl <= afterCall + 2,
                "TTL should be now + 24h (no stagger), got: " + actualTtl);
    }

    @Test
    void startDirectoryListing_dynamoUsesCorrectKeyField() {
        StartDirectoryListingResponse response = StartDirectoryListingResponse.builder()
                .listingId(LISTING_ID).build();
        when(transferClient.startDirectoryListing(sdkRequest)).thenReturn(response);

        helper.startDirectoryListing(sdkRequest, METADATA);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());
        UpdateItemRequest captured = captor.getValue();

        assertEquals(TABLE_NAME, captured.tableName());
        assertEquals(LISTING_ID, captured.key().get("jobId").s());
        assertEquals("attribute_not_exists(metadata)", captured.conditionExpression());
        assertEquals("ttl", captured.expressionAttributeNames().get("#t"));
    }
}
