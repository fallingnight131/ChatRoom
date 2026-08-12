package com.fallingnight.chat.persistence.postgres.migration;

/** Fixed-message PostgreSQL V1 message import failure. */
public final class V1MessageImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1MessageImportException(String message) {
        super(message);
    }

    public V1MessageImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
