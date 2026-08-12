package com.fallingnight.chat.persistence.postgres.migration;

/** Fixed-message failure from query-only V1 message payload extraction. */
public final class V1MessagePayloadSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1MessagePayloadSourceException(String message) {
        super(message);
    }

    V1MessagePayloadSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
