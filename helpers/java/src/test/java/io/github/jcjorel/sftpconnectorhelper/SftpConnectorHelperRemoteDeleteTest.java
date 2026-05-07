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
import software.amazon.awssdk.services.transfer.model.StartRemoteDeleteRequest;
import software.amazon.awssdk.services.transfer.model.StartRemoteDeleteResponse;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SftpConnectorHelperRemoteDeleteTest {

    private static final String TABLE_NAME = "test-table";
    private static final Duration TTL_DURATION = Duration.ofHours(24);
    private static final String DELETE_ID = "d-abc123";
    private static final String METADATA = "{\"key\":\"value\"}";

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private TransferClient transferClient;

    private SftpConnectorHelper helper;
    private StartRemoteDeleteRequest sdkRequest;

    @BeforeEach
    void setUp() {
        helper = new SftpConnectorHelper(TABLE_NAME, TTL_DURATION, dynamoDbClient, transferClient);
        sdkRequest = StartRemoteDeleteRequest.builder()
                .connectorId("c-123")
                .deletePath("/remote/file.txt")
                .build();
    }

    @Test
    void startRemoteDelete_success_returnsSuccess() {
        StartRemoteDeleteResponse response = StartRemoteDeleteResponse.builder()
                .deleteId(DELETE_ID).build();
        when(transferClient.startRemoteDelete(sdkRequest)).thenReturn(response);

        SftpOperationResult<StartRemoteDeleteResponse> result = helper.startRemoteDelete(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.Success.class, result);
        assertEquals(response, ((SftpOperationResult.Success<StartRemoteDeleteResponse>) result).response());
    }

    @Test
    void startRemoteDelete_sdkThrows_propagatesNoDynamo() {
        RuntimeException ex = new RuntimeException("SDK failure");
        when(transferClient.startRemoteDelete(sdkRequest)).thenThrow(ex);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> helper.startRemoteDelete(sdkRequest, METADATA));

        assertSame(ex, thrown);
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void startRemoteDelete_conditionalCheckFails_returnsMetadataAlreadyExists() {
        StartRemoteDeleteResponse response = StartRemoteDeleteResponse.builder()
                .deleteId(DELETE_ID).build();
        when(transferClient.startRemoteDelete(sdkRequest)).thenReturn(response);
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("exists").build());

        SftpOperationResult<StartRemoteDeleteResponse> result = helper.startRemoteDelete(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.MetadataAlreadyExists.class, result);
        var r = (SftpOperationResult.MetadataAlreadyExists<StartRemoteDeleteResponse>) result;
        assertEquals(response, r.response());
        assertEquals(DELETE_ID, r.jobId());
    }

    @Test
    void startRemoteDelete_dynamoThrows_returnsMetadataWriteFailed() {
        StartRemoteDeleteResponse response = StartRemoteDeleteResponse.builder()
                .deleteId(DELETE_ID).build();
        when(transferClient.startRemoteDelete(sdkRequest)).thenReturn(response);
        DynamoDbException dynamoEx = (DynamoDbException) DynamoDbException.builder().message("err").build();
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class))).thenThrow(dynamoEx);

        SftpOperationResult<StartRemoteDeleteResponse> result = helper.startRemoteDelete(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.MetadataWriteFailed.class, result);
        var r = (SftpOperationResult.MetadataWriteFailed<StartRemoteDeleteResponse>) result;
        assertEquals(response, r.response());
        assertEquals(DELETE_ID, r.jobId());
        assertSame(dynamoEx, r.cause());
    }

    @Test
    void startRemoteDelete_invalidMetadata_throwsNoSdkCall() {
        assertThrows(IllegalArgumentException.class,
                () -> helper.startRemoteDelete(sdkRequest, "not-json"));

        verify(transferClient, never()).startRemoteDelete(any(StartRemoteDeleteRequest.class));
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void startRemoteDelete_nullRequest_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> helper.startRemoteDelete(null, METADATA));
    }

    @Test
    void startRemoteDelete_ttlHasNoStagger() {
        StartRemoteDeleteResponse response = StartRemoteDeleteResponse.builder()
                .deleteId(DELETE_ID).build();
        when(transferClient.startRemoteDelete(sdkRequest)).thenReturn(response);

        long beforeCall = Instant.now().getEpochSecond() + TTL_DURATION.toSeconds();
        helper.startRemoteDelete(sdkRequest, METADATA);
        long afterCall = Instant.now().getEpochSecond() + TTL_DURATION.toSeconds();

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());
        long actualTtl = Long.parseLong(captor.getValue().expressionAttributeValues().get(":t").n());

        assertTrue(actualTtl >= beforeCall - 2 && actualTtl <= afterCall + 2,
                "TTL should be now + 24h (no stagger), got: " + actualTtl);
    }

    @Test
    void startRemoteDelete_dynamoUsesCorrectKeyField() {
        StartRemoteDeleteResponse response = StartRemoteDeleteResponse.builder()
                .deleteId(DELETE_ID).build();
        when(transferClient.startRemoteDelete(sdkRequest)).thenReturn(response);

        helper.startRemoteDelete(sdkRequest, METADATA);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());
        assertEquals(DELETE_ID, captor.getValue().key().get("jobId").s());
    }
}
