package io.github.jcjorel.sftpconnectorhelper.internal;

/**
 * Maps each SFTP Connector API operation to its response ID field name.
 * Internal class — not part of the public API.
 */
public enum OperationMapping {

    START_FILE_TRANSFER("transferId"),
    START_DIRECTORY_LISTING("listingId"),
    START_REMOTE_MOVE("moveId"),
    START_REMOTE_DELETE("deleteId");

    private final String responseIdField;

    OperationMapping(String responseIdField) {
        this.responseIdField = responseIdField;
    }

    public String getResponseIdField() {
        return responseIdField;
    }
}
