package com.fallingnight.chat.persistence.postgres.migration;

/** Safe failure from the query-only V1 message-state reader. */
public final class V1MessageStateSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1MessageStateSourceException(String message) {
        super(message);
    }

    V1MessageStateSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
