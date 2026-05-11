package io.github.jcjorel.sftpconnectorhelper;

/**
 * Base exception for all failures thrown by the SFTP Connector Helper library.
 *
 * <p>Extends {@link RuntimeException} (unchecked) to maintain consistency with the
 * AWS SDK exception model where SDK failures propagate as unchecked exceptions.</p>
 */
public abstract class SftpConnectorHelperException extends RuntimeException {

    /**
     * Constructs a new exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    protected SftpConnectorHelperException(String message, Throwable cause) {
        super(message, cause);
    }
}
