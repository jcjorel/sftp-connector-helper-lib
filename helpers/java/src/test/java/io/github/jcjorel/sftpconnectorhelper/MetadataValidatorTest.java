package io.github.jcjorel.sftpconnectorhelper;

import io.github.jcjorel.sftpconnectorhelper.internal.MetadataValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MetadataValidatorTest {

    @Test
    @DisplayName("Valid JSON object passes validation")
    void validJsonObjectPasses() {
        assertDoesNotThrow(() -> MetadataValidator.validate("{\"key\":\"value\"}"));
    }

    @Test
    @DisplayName("Valid empty JSON object passes validation")
    void validEmptyJsonObjectPasses() {
        assertDoesNotThrow(() -> MetadataValidator.validate("{}"));
    }

    @Test
    @DisplayName("Null metadata throws IllegalArgumentException")
    void nullThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate(null));
        assertEquals("Metadata must be a non-null JSON object", ex.getMessage());
    }

    @Test
    @DisplayName("Empty string throws IllegalArgumentException")
    void emptyStringThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate(""));
        assertEquals("Metadata must be a non-null JSON object", ex.getMessage());
    }

    @Test
    @DisplayName("JSON null literal throws IllegalArgumentException")
    void jsonNullLiteralThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate("null"));
        assertEquals("Metadata must be a JSON object, got: null", ex.getMessage());
    }

    @Test
    @DisplayName("Deeply nested JSON throws IllegalArgumentException")
    void deeplyNestedJsonThrows() {
        String nested = "{\"a\":".repeat(100) + "{}" + "}".repeat(100);
        assertThrows(IllegalArgumentException.class, () -> MetadataValidator.validate(nested));
    }

    @Test
    @DisplayName("JSON array throws IllegalArgumentException")
    void jsonArrayThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate("[1,2,3]"));
        assertEquals("Metadata must be a JSON object, got: array", ex.getMessage());
    }

    @Test
    @DisplayName("JSON string throws IllegalArgumentException")
    void jsonStringThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate("\"hello\""));
        assertEquals("Metadata must be a JSON object, got: string", ex.getMessage());
    }

    @Test
    @DisplayName("JSON number throws IllegalArgumentException")
    void jsonNumberThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate("42"));
        assertEquals("Metadata must be a JSON object, got: number", ex.getMessage());
    }

    @Test
    @DisplayName("JSON boolean throws IllegalArgumentException")
    void jsonBooleanThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate("true"));
        assertEquals("Metadata must be a JSON object, got: boolean", ex.getMessage());
    }

    @Test
    @DisplayName("Malformed JSON throws IllegalArgumentException")
    void malformedJsonThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate("{invalid"));
        assertTrue(ex.getMessage().startsWith("Metadata must be valid JSON:"));
    }

    @Test
    @DisplayName("Exactly 25,000 bytes passes validation (boundary inclusive)")
    void exactly25000BytesPasses() {
        // {"x":"<filler>"} = 8 bytes overhead, need 24,992 filler chars
        String filler = "a".repeat(24_992);
        String metadata = "{\"x\":\"" + filler + "\"}";
        assertEquals(25_000, metadata.getBytes(StandardCharsets.UTF_8).length);
        assertDoesNotThrow(() -> MetadataValidator.validate(metadata));
    }

    @Test
    @DisplayName("25,001 bytes throws with correct message format")
    void exceeding25000BytesThrows() {
        String filler = "a".repeat(24_993);
        String metadata = "{\"x\":\"" + filler + "\"}";
        assertEquals(25_001, metadata.getBytes(StandardCharsets.UTF_8).length);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate(metadata));
        assertEquals("Metadata size (25001 bytes) exceeds maximum allowed size (25000 bytes)", ex.getMessage());
    }

    @Test
    @DisplayName("Error message for oversized includes actual byte count")
    void oversizedMessageIncludesActualByteCount() {
        String filler = "a".repeat(25_000);
        String metadata = "{\"x\":\"" + filler + "\"}";
        int expectedBytes = metadata.getBytes(StandardCharsets.UTF_8).length;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MetadataValidator.validate(metadata));
        assertTrue(ex.getMessage().contains(String.valueOf(expectedBytes)));
    }

    @Test
    @DisplayName("Multi-byte UTF-8 characters counted correctly")
    void multiBytUtf8CountedCorrectly() {
        // 🎉 = 4 bytes in UTF-8
        // {"x":"..."} = 8 bytes overhead. Content needed for 25,001 total: 24,993 bytes
        // Use 24,989 ASCII chars + 1 emoji (4 bytes) = 24,993 content bytes
        String filler = "a".repeat(24_989) + "🎉";
        String metadata = "{\"x\":\"" + filler + "\"}";
        assertEquals(25_001, metadata.getBytes(StandardCharsets.UTF_8).length);
        assertThrows(IllegalArgumentException.class, () -> MetadataValidator.validate(metadata));
    }
}
