package io.github.jcjorel.sftpconnectorhelper;

/**
 * Options controlling event emission behavior for {@code startFileTransfer} operations.
 *
 * <p>Use {@link #defaults()} for backward-compatible behavior (individual events only),
 * or {@link #builder()} to configure batch completion event emission.</p>
 */
public final class FileTransferOptions {

    private final EventEmissionMode emissionMode;

    private FileTransferOptions(EventEmissionMode emissionMode) {
        this.emissionMode = emissionMode;
    }

    /** Returns the configured emission mode. */
    public EventEmissionMode emissionMode() {
        return emissionMode;
    }

    /** Returns default options (individual file events only, backward-compatible). */
    public static FileTransferOptions defaults() {
        return new FileTransferOptions(EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY);
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private EventEmissionMode emissionMode = EventEmissionMode.INDIVIDUAL_FILE_EVENTS_ONLY;

        private Builder() {}

        public Builder emissionMode(EventEmissionMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("emissionMode must not be null");
            }
            this.emissionMode = mode;
            return this;
        }

        public FileTransferOptions build() {
            return new FileTransferOptions(emissionMode);
        }
    }
}
