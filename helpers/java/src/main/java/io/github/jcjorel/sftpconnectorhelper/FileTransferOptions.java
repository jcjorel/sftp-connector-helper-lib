package io.github.jcjorel.sftpconnectorhelper;

import java.time.Duration;

/**
 * Options controlling event emission behavior for {@code startFileTransfer} operations.
 *
 * <p>Use {@link #defaults()} for backward-compatible behavior (individual events only),
 * or {@link #builder()} to configure batch completion event emission.</p>
 *
 * <p><b>Thread Safety:</b> Instances of this class are immutable and thread-safe.</p>
 *
 * @see SftpConnectorHelper#startFileTransfer(software.amazon.awssdk.services.transfer.model.StartFileTransferRequest, String, FileTransferOptions)
 * @see EventEmissionMode
 */
public final class FileTransferOptions {

    /** Default batch timeout duration (1 hour) applied when batch mode is active. */
    public static final Duration DEFAULT_BATCH_TIMEOUT = Duration.ofHours(1);

    /**
     * Minimum allowed batch timeout (2 seconds).
     *
     * <p><b>Warning:</b> Values below 15 minutes are impractical for production use.
     * SFTP Connector transfers typically take several seconds per file, and the timeout
     * mechanism has limited time granularity. This low minimum exists to support
     * testing scenarios with short-lived transfers.</p>
     */
    public static final Duration MIN_BATCH_TIMEOUT = Duration.ofSeconds(2);

    private final EventEmissionMode emissionMode;
    private final Duration batchTimeout;

    private FileTransferOptions(EventEmissionMode emissionMode, Duration batchTimeout) {
        this.emissionMode = emissionMode;
        this.batchTimeout = batchTimeout;
    }

    /**
     * Returns the configured emission mode.
     *
     * @return the event emission mode
     */
    public EventEmissionMode emissionMode() {
        return emissionMode;
    }

    /**
     * Returns the batch timeout duration.
     *
     * <p>When non-zero and emission mode is not {@code INDIVIDUAL_FILE_EVENTS_ONLY},
     * a timeout event is published if not all files resolve within this duration.</p>
     *
     * @return the batch timeout duration, or {@link Duration#ZERO} if disabled
     */
    public Duration batchTimeout() {
        return batchTimeout;
    }

    /**
     * Returns default options (individual file events only, backward-compatible).
     *
     * @return default options instance
     */
    public static FileTransferOptions defaults() {
        return new FileTransferOptions(EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY, Duration.ZERO);
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link FileTransferOptions}.
     *
     * @see FileTransferOptions
     */
    public static final class Builder {
        private EventEmissionMode emissionMode = EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY;
        private Duration batchTimeout = null;

        private Builder() {}

        /**
         * Sets the event emission mode.
         *
         * @param mode the emission mode; must not be null
         * @return this builder
         * @throws IllegalArgumentException if {@code mode} is null.
         */
        public Builder emissionMode(EventEmissionMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("emissionMode must not be null");
            }
            this.emissionMode = mode;
            return this;
        }

        /**
         * Sets the batch timeout duration.
         *
         * <p>Must be at least {@link FileTransferOptions#MIN_BATCH_TIMEOUT} or
         * {@link Duration#ZERO} to disable. Values between zero and the minimum are rejected.</p>
         *
         * <p><b>Note:</b> For production use, values of 15 minutes or more are recommended.
         * Lower values exist only for integration testing purposes.</p>
         *
         * @param timeout the timeout duration
         * @return this builder
         * @throws IllegalArgumentException if timeout is non-zero and less than {@link FileTransferOptions#MIN_BATCH_TIMEOUT}
         */
        public Builder batchTimeout(Duration timeout) {
            this.batchTimeout = timeout;
            return this;
        }

        /**
         * Builds the options instance.
         *
         * @return a new {@link FileTransferOptions} instance
         * @throws IllegalArgumentException if batchTimeout is non-zero and less than {@link FileTransferOptions#MIN_BATCH_TIMEOUT}
         */
        public FileTransferOptions build() {
            Duration effectiveTimeout;
            if (emissionMode == EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY) {
                effectiveTimeout = Duration.ZERO;
            } else {
                effectiveTimeout = (batchTimeout != null) ? batchTimeout : DEFAULT_BATCH_TIMEOUT;
                if (!effectiveTimeout.isZero() && effectiveTimeout.compareTo(MIN_BATCH_TIMEOUT) < 0) {
                    throw new IllegalArgumentException(
                            "batchTimeout must be >= " + MIN_BATCH_TIMEOUT + " or Duration.ZERO (disabled), got: " + effectiveTimeout);
                }
            }
            return new FileTransferOptions(emissionMode, effectiveTimeout);
        }
    }
}
