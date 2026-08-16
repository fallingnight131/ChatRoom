package com.fallingnight.chat.persistence.postgres;

public final class ContactPersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ContactPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
