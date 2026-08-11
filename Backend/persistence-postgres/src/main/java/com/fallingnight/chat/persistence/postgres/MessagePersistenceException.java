package com.fallingnight.chat.persistence.postgres;

/** Safe category for unexpected durable-message storage failures. */
public final class MessagePersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MessagePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
