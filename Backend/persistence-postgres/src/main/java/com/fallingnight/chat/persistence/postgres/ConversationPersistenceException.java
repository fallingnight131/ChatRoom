package com.fallingnight.chat.persistence.postgres;

/** Safe infrastructure failure for conversation directory access. */
public final class ConversationPersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConversationPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
