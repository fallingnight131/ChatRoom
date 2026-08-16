package com.fallingnight.chat.persistence.postgres;

/** Fail-closed storage failure at the detached Notification module boundary. */
public final class NotificationPersistenceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotificationPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
