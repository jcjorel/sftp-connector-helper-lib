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
import software.amazon.awssdk.services.transfer.model.StartFileTransferRequest;
import software.amazon.awssdk.services.transfer.model.StartFileTransferResponse;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SftpConnectorHelperTest {

    private static final String TABLE_NAME = "test-table";
    private static final Duration TTL_DURATION = Duration.ofHours(24);
    private static final String TRANSFER_ID = "t-abc123";
    private static final String METADATA = "{\"key\":\"value\"}";

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private TransferClient transferClient;

    private SftpConnectorHelper helper;
    private StartFileTransferRequest sdkRequest;

    @BeforeEach
    void setUp() {
        helper = new SftpConnectorHelper(TABLE_NAME, TTL_DURATION, dynamoDbClient, transferClient);
        sdkRequest = StartFileTransferRequest.builder()
                .connectorId("c-123")
                .sendFilePaths("/remote/file.txt")
                .build();
    }

    @Test
    void startFileTransfer_successfulSdkCallAndDynamoWrite_returnsSuccess() {
        StartFileTransferResponse response = StartFileTransferResponse.builder()
                .transferId(TRANSFER_ID)
                .build();
        when(transferClient.startFileTransfer(sdkRequest)).thenReturn(response);

        SftpOperationResult<StartFileTransferResponse> result = helper.startFileTransfer(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.Success.class, result);
        assertEquals(response, ((SftpOperationResult.Success<StartFileTransferResponse>) result).response());
    }

    @Test
    void startFileTransfer_sdkCallThrows_exceptionPropagatesNoDynamoInteraction() {
        RuntimeException sdkException = new RuntimeException("SDK failure");
        when(transferClient.startFileTransfer(sdkRequest)).thenThrow(sdkException);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> helper.startFileTransfer(sdkRequest, METADATA));

        assertSame(sdkException, thrown);
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void startFileTransfer_dynamoConditionalCheckFails_returnsMetadataAlreadyExists() {
        StartFileTransferResponse response = StartFileTransferResponse.builder()
                .transferId(TRANSFER_ID)
                .build();
        when(transferClient.startFileTransfer(sdkRequest)).thenReturn(response);
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("exists").build());

        SftpOperationResult<StartFileTransferResponse> result = helper.startFileTransfer(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.MetadataAlreadyExists.class, result);
        var metadataExists = (SftpOperationResult.MetadataAlreadyExists<StartFileTransferResponse>) result;
        assertEquals(response, metadataExists.response());
        assertEquals(TRANSFER_ID, metadataExists.jobId());
    }

    @Test
    void startFileTransfer_dynamoThrowsOtherException_returnsMetadataWriteFailed() {
        StartFileTransferResponse response = StartFileTransferResponse.builder()
                .transferId(TRANSFER_ID)
                .build();
        when(transferClient.startFileTransfer(sdkRequest)).thenReturn(response);
        DynamoDbException dynamoException = (DynamoDbException) DynamoDbException.builder()
                .message("throttled").build();
        when(dynamoDbClient.updateItem(any(UpdateItemRequest.class))).thenThrow(dynamoException);

        SftpOperationResult<StartFileTransferResponse> result = helper.startFileTransfer(sdkRequest, METADATA);

        assertInstanceOf(SftpOperationResult.MetadataWriteFailed.class, result);
        var writeFailed = (SftpOperationResult.MetadataWriteFailed<StartFileTransferResponse>) result;
        assertEquals(response, writeFailed.response());
        assertEquals(TRANSFER_ID, writeFailed.jobId());
        assertSame(dynamoException, writeFailed.cause());
    }

    @Test
    void startFileTransfer_invalidMetadata_throwsIllegalArgumentExceptionNoSdkCall() {
        assertThrows(IllegalArgumentException.class,
                () -> helper.startFileTransfer(sdkRequest, "not-json"));

        verify(transferClient, never()).startFileTransfer(any(StartFileTransferRequest.class));
        verify(dynamoDbClient, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void startFileTransfer_ttlCalculationIncludesStagger() {
        StartFileTransferResponse response = StartFileTransferResponse.builder()
                .transferId(TRANSFER_ID)
                .build();
        when(transferClient.startFileTransfer(sdkRequest)).thenReturn(response);

        long beforeCall = Instant.now().getEpochSecond() + TTL_DURATION.toSeconds() + 3600;
        helper.startFileTransfer(sdkRequest, METADATA);
        long afterCall = Instant.now().getEpochSecond() + TTL_DURATION.toSeconds() + 3600;

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());
        long actualTtl = Long.parseLong(captor.getValue().expressionAttributeValues().get(":t").n());

        assertTrue(actualTtl >= beforeCall - 2 && actualTtl <= afterCall + 2,
                "TTL should be now + 24h + 3600s (stagger), got: " + actualTtl);
    }

    @Test
    void startFileTransfer_dynamoUpdateItemUsesCorrectParameters() {
        StartFileTransferResponse response = StartFileTransferResponse.builder()
                .transferId(TRANSFER_ID)
                .build();
        when(transferClient.startFileTransfer(sdkRequest)).thenReturn(response);

        helper.startFileTransfer(sdkRequest, METADATA);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient).updateItem(captor.capture());
        UpdateItemRequest captured = captor.getValue();

        assertEquals(TABLE_NAME, captured.tableName());
        assertEquals(TRANSFER_ID, captured.key().get("jobId").s());
        assertEquals("SET metadata = :m, #t = :t", captured.updateExpression());
        assertEquals("attribute_not_exists(metadata)", captured.conditionExpression());
        assertEquals("ttl", captured.expressionAttributeNames().get("#t"));
        assertEquals(METADATA, captured.expressionAttributeValues().get(":m").s());
        assertNotNull(captured.expressionAttributeValues().get(":t").n());
    }
}
