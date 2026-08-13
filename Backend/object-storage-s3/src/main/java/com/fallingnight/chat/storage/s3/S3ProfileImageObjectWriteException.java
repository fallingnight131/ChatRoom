package com.fallingnight.chat.storage.s3;

public final class S3ProfileImageObjectWriteException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public S3ProfileImageObjectWriteException(String message) { super(message); }
    public S3ProfileImageObjectWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
