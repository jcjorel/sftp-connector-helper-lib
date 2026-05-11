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
import software.amazon.awssdk.services.transfer.model.StartRemoteMoveRequest;
import software.amazon.awssdk.services.transfer.model.StartRemoteMoveResponse;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SftpConnectorHelperRemoteMoveTest {

    private static final String TABLE_NAME = "test-table";
    private static final Duration TTL_DURATION = Duration.ofHours(24);
    private static final String MOVE_ID = "m-abc123";
    private static final String METADATA = "{\"key\":\"value\"}";

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private TransferClient transferClient;

    private SftpConnectorHelper helper;
    private StartRemoteMoveRequest sdkRequest;

    @BeforeEach
    void setUp() {
        helper = new SftpConnectorHelper(TABLE_NAME, TTL_DURATION, dynamoDbClient, transferClient);
        sdkRequest = StartRemoteMoveRequest.builder()
                .connectorId("c-123")
                .sourcePath("/remote/source.txt")
                .targetPath("/remote/dest.txt")
                .build();
    }

    @Test
    void startRemoteMove_success_returnsResponse() {
        StartRemoteMoveResponse response = StartRemoteMoveResponse.builder()
                .moveId(MOVE_ID).build();
        when(transferClient.startRemoteMove(sdkRequest)).thenReturn(response);

        StartRemoteMoveResponse result = helper.startRemoteMove(sdkRequest, METADATA);

        assertSame(response, result);
    }

    @Test
    void startRemoteMove_sdkThrows_propagatesNoDynamo() {
        RuntimeException ex = new RuntimeException("SDK failure");
        when(transferClient.startRemoteMove(sdkRequest)).thenThrow(ex);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> helper.startRemoteMove(sdkRequest, METADATA));

        assertSame(ex, thrown);
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void startRemoteMove_conditionalCheckFails_throwsMetadataWriteException() {
        StartRemoteMoveResponse response = StartRemoteMoveResponse.builder()
                .moveId(MOVE_ID).build();
        when(transferClient.startRemoteMove(sdkRequest)).thenReturn(response);
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("exists").build());

        MetadataWriteException ex = assertThrows(MetadataWriteException.class,
                () -> helper.startRemoteMove(sdkRequest, METADATA));

        assertEquals(MOVE_ID, ex.getJobId());
        assertSame(response, ex.getSdkResponse());
        assertTrue(ex.getMessage().contains("Unexpected duplicate metadata"));
    }

    @Test
    void startRemoteMove_dynamoThrows_throwsMetadataWriteException() {
        StartRemoteMoveResponse response = StartRemoteMoveResponse.builder()
                .moveId(MOVE_ID).build();
        when(transferClient.startRemoteMove(sdkRequest)).thenReturn(response);
        DynamoDbException dynamoEx = (DynamoDbException) DynamoDbException.builder().message("err").build();
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class))).thenThrow(dynamoEx);

        MetadataWriteException ex = assertThrows(MetadataWriteException.class,
                () -> helper.startRemoteMove(sdkRequest, METADATA));

        assertEquals(MOVE_ID, ex.getJobId());
        assertSame(response, ex.getSdkResponse());
        assertSame(dynamoEx, ex.getCause());
    }

    @Test
    void startRemoteMove_invalidMetadata_throwsNoSdkCall() {
        assertThrows(IllegalArgumentException.class,
                () -> helper.startRemoteMove(sdkRequest, "not-json"));

        verify(transferClient, never()).startRemoteMove(any(StartRemoteMoveRequest.class));
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void startRemoteMove_nullRequest_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> helper.startRemoteMove(null, METADATA));
    }

    @Test
    void startRemoteMove_ttlHasNoStagger() {
        StartRemoteMoveResponse response = StartRemoteMoveResponse.builder()
                .moveId(MOVE_ID).build();
        when(transferClient.startRemoteMove(sdkRequest)).thenReturn(response);

        long beforeCall = Instant.now().getEpochSecond() + TTL_DURATION.toSeconds();
        helper.startRemoteMove(sdkRequest, METADATA);
        long afterCall = Instant.now().getEpochSecond() + TTL_DURATION.toSeconds();

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());
        long actualTtl = Long.parseLong(captor.getValue().expressionAttributeValues().get(":t").n());

        assertTrue(actualTtl >= beforeCall - 2 && actualTtl <= afterCall + 2,
                "TTL should be now + 24h (no stagger), got: " + actualTtl);
    }

    @Test
    void startRemoteMove_dynamoUsesCorrectKeyField() {
        StartRemoteMoveResponse response = StartRemoteMoveResponse.builder()
                .moveId(MOVE_ID).build();
        when(transferClient.startRemoteMove(sdkRequest)).thenReturn(response);

        helper.startRemoteMove(sdkRequest, METADATA);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());
        assertEquals(MOVE_ID, captor.getValue().key().get("jobId").s());
    }
}
