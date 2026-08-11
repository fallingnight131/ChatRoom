package com.fallingnight.chat.persistence.postgres.migration;

/** Safe migration-source failure without database path, SQL, or row material. */
public final class V1IdentitySourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1IdentitySourceException(String message) {
        super(message);
    }

    V1IdentitySourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
