package io.github.jcjorel.sftpconnectorhelper;

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

    private final EventEmissionMode emissionMode;

    private FileTransferOptions(EventEmissionMode emissionMode) {
        this.emissionMode = emissionMode;
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
     * Returns default options (individual file events only, backward-compatible).
     *
     * @return default options instance
     */
    public static FileTransferOptions defaults() {
        return new FileTransferOptions(EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY);
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
         * Builds the options instance.
         *
         * @return a new {@link FileTransferOptions} instance
         */
        public FileTransferOptions build() {
            return new FileTransferOptions(emissionMode);
        }
    }
}
