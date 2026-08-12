package com.fallingnight.chat.persistence.postgres.migration;

/** Fixed-message target failure that must not disclose contact or database data. */
public final class V1ContactRequestImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1ContactRequestImportException(String message) {
        super(message);
    }

    V1ContactRequestImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
