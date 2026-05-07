package io.github.jcjorel.sftpconnectorhelper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters directory listing S3 output by file path and directory path regex patterns.
 * Accepts both raw EventBridge events and enriched events (with {@code _helper_metadata}).
 * This is a read-only utility — no S3 writes are performed.
 */
public final class DirectoryListingFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final S3Client s3Client;

    /**
     * @param s3Client S3 client used to read the directory listing output
     */
    public DirectoryListingFilter(S3Client s3Client) {
        if (s3Client == null) {
            throw new IllegalArgumentException("s3Client must not be null");
        }
        this.s3Client = s3Client;
    }

    /**
     * Filters a directory listing result referenced by an EventBridge event.
     *
     * @param eventJson  EventBridge event JSON (raw or enriched)
     * @param fileRegex  regex for filePath filtering: null=include all, ""=exclude all, otherwise Matcher.find()
     * @param pathRegex  regex for path filtering: null=include all, ""=exclude all, otherwise Matcher.find()
     * @return filtered result preserving the original truncated flag
     * @throws IOException if JSON parsing or S3 read fails
     */
    public DirectoryListingResult filter(String eventJson, String fileRegex, String pathRegex) throws IOException {
        JsonNode event = MAPPER.readTree(eventJson);
        JsonNode location = event.at("/detail/output-file-location");
        if (location.isMissingNode() || !location.has("bucket") || !location.has("key")) {
            throw new IllegalArgumentException("Event does not contain detail.output-file-location with bucket and key");
        }

        String bucket = location.get("bucket").asText();
        String key = location.get("key").asText();
        String content = loadS3Content(bucket, key);

        JsonNode listing = MAPPER.readTree(content);

        List<Map<String, Object>> files = MAPPER.convertValue(
                listing.get("files"), new TypeReference<List<Map<String, Object>>>() {});
        List<Map<String, Object>> paths = MAPPER.convertValue(
                listing.get("paths"), new TypeReference<List<Map<String, Object>>>() {});
        String truncated = listing.has("truncated") ? listing.get("truncated").asText() : "false";

        if (files == null) files = Collections.emptyList();
        if (paths == null) paths = Collections.emptyList();

        List<Map<String, Object>> filteredFiles = applyFilter(files, "filePath", fileRegex);
        List<Map<String, Object>> filteredPaths = applyFilter(paths, "path", pathRegex);

        return new DirectoryListingResult(filteredFiles, filteredPaths, truncated);
    }

    private List<Map<String, Object>> applyFilter(List<Map<String, Object>> entries, String field, String regex) {
        if (regex == null) {
            return List.copyOf(entries);
        }
        if (regex.isEmpty()) {
            return Collections.emptyList();
        }
        Pattern pattern = Pattern.compile(regex);
        return entries.stream()
                .filter(entry -> {
                    Object value = entry.get(field);
                    if (value == null) return false;
                    Matcher matcher = pattern.matcher(value.toString());
                    return matcher.find();
                })
                .toList();
    }

    private String loadS3Content(String bucket, String key) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
            return new String(response.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
