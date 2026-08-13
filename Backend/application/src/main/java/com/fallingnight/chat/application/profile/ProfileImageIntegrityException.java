package com.fallingnight.chat.application.profile;

/** Durable metadata and private object storage disagree. */
public final class ProfileImageIntegrityException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public ProfileImageIntegrityException(String message) { super(message); }
    public ProfileImageIntegrityException(String message, Throwable cause) { super(message, cause); }
}
