package io.github.jcjorel.sftpconnectorhelper.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Validates metadata before SDK calls.
 * Internal class — not part of the public API.
 */
public final class MetadataValidator {

    private static final int MAX_METADATA_BYTES = 25_000;
    private static final int MAX_NESTING_DEPTH = 50;
    private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

    private MetadataValidator() {
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(MAX_NESTING_DEPTH).build());
        return mapper;
    }

    /**
     * Validates that metadata is a non-null JSON object within size limits.
     *
     * @param metadata the metadata JSON string to validate
     * @throws IllegalArgumentException if metadata is null, not a JSON object, or exceeds size limit
     */
    public static void validate(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            throw new IllegalArgumentException("Metadata must be a non-null JSON object");
        }

        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Metadata must be valid JSON: " + e.getMessage());
        }

        if (!node.isObject()) {
            throw new IllegalArgumentException("Metadata must be a JSON object, got: " + node.getNodeType().name().toLowerCase());
        }

        int byteLength = metadata.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException(
                    "Metadata size (" + byteLength + " bytes) exceeds maximum allowed size (" + MAX_METADATA_BYTES + " bytes)");
        }
    }
}
