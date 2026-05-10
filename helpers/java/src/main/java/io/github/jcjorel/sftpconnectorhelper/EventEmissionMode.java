package io.github.jcjorel.sftpconnectorhelper;

/**
 * Controls which events are published to EventBridge for a file transfer operation.
 */
public enum EventEmissionMode {
    /** Publish only individual per-file enriched events (default, backward-compatible). */
    INDIVIDUAL_FILE_EVENTS_ONLY,
    /** Publish both individual per-file events and a batch completion event. */
    INDIVIDUAL_AND_WHOLE_TRANSFER_COMPLETION,
    /** Publish only the batch completion event; suppress individual per-file events. */
    WHOLE_TRANSFER_COMPLETION_ONLY
}
