package com.fallingnight.chat.persistence.postgres.migration;

/** Fixed-message target failure that must not disclose conversation data. */
public final class V1ConversationImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1ConversationImportException(String message) {
        super(message);
    }

    public V1ConversationImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
