package com.fallingnight.chat.persistence.postgres.migration;

/** Fixed-message source failure that must not disclose paths or contact data. */
public final class V1ContactRequestSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1ContactRequestSourceException(String message) {
        super(message);
    }

    V1ContactRequestSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
