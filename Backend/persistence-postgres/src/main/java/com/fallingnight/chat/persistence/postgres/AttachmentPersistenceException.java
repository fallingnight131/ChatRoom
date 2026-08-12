package com.fallingnight.chat.persistence.postgres;

/** Fixed safe boundary for unexpected attachment metadata persistence failures. */
public final class AttachmentPersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AttachmentPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
