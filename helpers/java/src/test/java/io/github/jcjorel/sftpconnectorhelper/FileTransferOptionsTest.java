package io.github.jcjorel.sftpconnectorhelper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferOptionsTest {

    @Test
    void defaults_returnsIndividualFileEventsOnly() {
        FileTransferOptions options = FileTransferOptions.defaults();
        assertEquals(EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY, options.emissionMode());
    }

    @Test
    void builder_defaultMode_isIndividualFileEventsOnly() {
        FileTransferOptions options = FileTransferOptions.builder().build();
        assertEquals(EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY, options.emissionMode());
    }

    @Test
    void builder_withWholeTransferCompletion() {
        FileTransferOptions options = FileTransferOptions.builder()
                .emissionMode(EventEmissionMode.INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION)
                .build();
        assertEquals(EventEmissionMode.INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION, options.emissionMode());
    }

    @Test
    void builder_withWholeTransferOnly() {
        FileTransferOptions options = FileTransferOptions.builder()
                .emissionMode(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY)
                .build();
        assertEquals(EventEmissionMode.WHOLE_TRANSFER_COMPLETION_ONLY, options.emissionMode());
    }

    @Test
    void builder_nullMode_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                FileTransferOptions.builder().emissionMode(null));
    }

    @Test
    void enumValues_hasThreeValues() {
        assertEquals(3, EventEmissionMode.values().length);
    }
}
