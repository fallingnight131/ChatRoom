package com.fallingnight.chat.persistence.postgres.migration;

/** Fixed-message source failure that must not disclose paths or conversation data. */
public final class V1ConversationSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1ConversationSourceException(String message) {
        super(message);
    }

    V1ConversationSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
