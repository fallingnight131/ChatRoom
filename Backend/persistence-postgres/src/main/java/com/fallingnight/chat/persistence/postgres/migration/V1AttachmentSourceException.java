package com.fallingnight.chat.persistence.postgres.migration;

/** Fixed-message source failure that must not disclose attachment locators. */
public final class V1AttachmentSourceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1AttachmentSourceException(String message) {
        super(message);
    }

    V1AttachmentSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
