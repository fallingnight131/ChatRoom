package com.fallingnight.chat.persistence.postgres;

import java.io.Serial;

/** Hides JDBC details from the application and transport layers. */
public final class IdentityPersistenceException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public IdentityPersistenceException(String message) {
        super(message);
    }

    public IdentityPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
