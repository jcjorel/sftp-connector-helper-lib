package io.github.jcjorel.sftpconnectorhelper.internal;

/**
 * Maps each SFTP Connector API operation to its response ID field name.
 * Internal class — not part of the public API.
 */
public enum OperationMapping {

    /** File transfer operation, keyed by transferId. */
    START_FILE_TRANSFER("transferId"),
    /** Directory listing operation, keyed by listingId. */
    START_DIRECTORY_LISTING("listingId"),
    /** Remote move operation, keyed by moveId. */
    START_REMOTE_MOVE("moveId"),
    /** Remote delete operation, keyed by deleteId. */
    START_REMOTE_DELETE("deleteId");

    private final String responseIdField;

    /**
     * Creates a mapping with the given response ID field name.
     *
     * @param responseIdField the name of the ID field in the SDK response
     */
    OperationMapping(String responseIdField) {
        this.responseIdField = responseIdField;
    }

    /**
     * Returns the response ID field name for this operation.
     *
     * @return the field name
     */
    public String getResponseIdField() {
        return responseIdField;
    }
}
