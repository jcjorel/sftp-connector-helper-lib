package io.github.jcjorel.sftpconnectorhelper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SftpOperationResultTest {

    @Test
    void sealedInterfacePermitsExactlyThreeSubtypes() {
        var permitted = SftpOperationResult.class.getPermittedSubclasses();
        assertNotNull(permitted);
        assertEquals(3, permitted.length);
    }

    @Test
    void successHoldsResponse() {
        var result = new SftpOperationResult.Success<>("response-value");
        assertEquals("response-value", result.response());
        assertInstanceOf(SftpOperationResult.class, result);
    }

    @Test
    void metadataWriteFailedHoldsResponseJobIdAndCause() {
        var cause = new RuntimeException("dynamo error");
        var result = new SftpOperationResult.MetadataWriteFailed<>("resp", "job-123", cause);
        assertEquals("resp", result.response());
        assertEquals("job-123", result.jobId());
        assertSame(cause, result.cause());
    }

    @Test
    void metadataAlreadyExistsHoldsResponseAndJobId() {
        var result = new SftpOperationResult.MetadataAlreadyExists<>("resp", "job-456");
        assertEquals("resp", result.response());
        assertEquals("job-456", result.jobId());
    }
}
