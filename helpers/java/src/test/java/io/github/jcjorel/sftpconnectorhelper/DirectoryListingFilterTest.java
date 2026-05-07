package io.github.jcjorel.sftpconnectorhelper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DirectoryListingFilterTest {

    private static final String S3_LISTING_CONTENT = """
            {
                "files": [
                    {"filePath": "/home/data/report.csv", "modifiedTimestamp": "2024-01-30T20:34:54Z", "size": 2323},
                    {"filePath": "/home/data/image.png", "modifiedTimestamp": "2024-01-30T20:34:54Z", "size": 4691},
                    {"filePath": "/home/logs/app.log", "size": 100},
                    {"filePath": "/archive/old.csv", "size": 50}
                ],
                "paths": [
                    {"path": "/home/data"},
                    {"path": "/home/logs"},
                    {"path": "/archive"},
                    {"path": "/home/data/subdir"}
                ],
                "truncated": "false"
            }
            """;

    private static final String RAW_EVENT = """
            {
                "source": "aws.transfer",
                "detail-type": "SFTP Connector Directory Listing Completed",
                "detail": {
                    "listing-id": "l-list001",
                    "connector-id": "c-01234567890abcdef",
                    "status-code": "COMPLETED",
                    "output-file-location": {
                        "domain": "S3",
                        "bucket": "my-bucket",
                        "key": "listings/output.json"
                    }
                }
            }
            """;

    private static final String ENRICHED_EVENT = """
            {
                "source": "aws.transfer",
                "detail-type": "SFTP Connector Directory Listing Completed",
                "detail": {
                    "listing-id": "l-list001",
                    "connector-id": "c-01234567890abcdef",
                    "status-code": "COMPLETED",
                    "output-file-location": {
                        "domain": "S3",
                        "bucket": "my-bucket",
                        "key": "listings/output.json"
                    },
                    "_helper_metadata": {"businessKey": "value123"}
                }
            }
            """;

    @Mock
    private S3Client s3Client;

    private DirectoryListingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new DirectoryListingFilter(s3Client);
    }

    private void mockS3Response(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);
    }

    @Test
    void nullFileRegexIncludesAllFiles() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, null, null);

        assertEquals(4, result.files().size());
    }

    @Test
    void emptyStringFileRegexExcludesAllFiles() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, "", null);

        assertEquals(0, result.files().size());
    }

    @Test
    void patternFileRegexFiltersCorrectly() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, ".*\\.csv", null);

        assertEquals(2, result.files().size());
        assertTrue(result.files().stream()
                .allMatch(f -> f.get("filePath").toString().endsWith(".csv")));
    }

    @Test
    void nullPathRegexIncludesAllPaths() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, null, null);

        assertEquals(4, result.paths().size());
    }

    @Test
    void emptyStringPathRegexExcludesAllPaths() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, null, "");

        assertEquals(0, result.paths().size());
    }

    @Test
    void patternPathRegexFiltersCorrectly() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, null, "/home/data.*");

        assertEquals(2, result.paths().size());
        assertTrue(result.paths().stream()
                .allMatch(p -> p.get("path").toString().startsWith("/home/data")));
    }

    @Test
    void combinedFileAndPathFiltering() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, ".*\\.csv", "/home/data.*");

        assertEquals(2, result.files().size());
        assertEquals(2, result.paths().size());
    }

    @Test
    void truncatedFlagPreservedFalse() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, null, null);

        assertEquals("false", result.truncated());
    }

    @Test
    void truncatedFlagPreservedTrue() throws IOException {
        String contentWithTruncated = S3_LISTING_CONTENT.replace("\"truncated\": \"false\"", "\"truncated\": \"true\"");
        mockS3Response(contentWithTruncated);

        DirectoryListingResult result = filter.filter(RAW_EVENT, null, null);

        assertEquals("true", result.truncated());
    }

    @Test
    void worksWithRawEventBridgeEvent() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(RAW_EVENT, ".*\\.csv", null);

        assertEquals(2, result.files().size());
        verify(s3Client).getObject(argThat((GetObjectRequest req) ->
                "my-bucket".equals(req.bucket()) && "listings/output.json".equals(req.key())));
    }

    @Test
    void worksWithEnrichedEventFormat() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        DirectoryListingResult result = filter.filter(ENRICHED_EVENT, ".*\\.csv", null);

        assertEquals(2, result.files().size());
        verify(s3Client).getObject(argThat((GetObjectRequest req) ->
                "my-bucket".equals(req.bucket()) && "listings/output.json".equals(req.key())));
    }

    @Test
    void s3ClientMockedNoRealCalls() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        filter.filter(RAW_EVENT, null, null);

        verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
        verifyNoMoreInteractions(s3Client);
    }

    @Test
    void constructorRejectsNullS3Client() {
        assertThrows(IllegalArgumentException.class, () -> new DirectoryListingFilter(null));
    }

    @Test
    void findSemanticsPartialMatch() throws IOException {
        mockS3Response(S3_LISTING_CONTENT);

        // "log" should match "/home/logs/app.log" via find() (partial match)
        DirectoryListingResult result = filter.filter(RAW_EVENT, "log", null);

        assertEquals(1, result.files().size());
        assertEquals("/home/logs/app.log", result.files().get(0).get("filePath"));
    }
}
