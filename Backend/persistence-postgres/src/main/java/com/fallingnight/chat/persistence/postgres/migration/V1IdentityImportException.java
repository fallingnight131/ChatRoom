package com.fallingnight.chat.persistence.postgres.migration;

/** Fixed-message migration failure that must not disclose account or credential material. */
public final class V1IdentityImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1IdentityImportException(String message) {
        super(message);
    }

    public V1IdentityImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
