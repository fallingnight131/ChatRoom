package com.fallingnight.chat.storage.s3;

public final class S3ProfileImageObjectReadException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public S3ProfileImageObjectReadException(String message) { super(message); }
    public S3ProfileImageObjectReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
