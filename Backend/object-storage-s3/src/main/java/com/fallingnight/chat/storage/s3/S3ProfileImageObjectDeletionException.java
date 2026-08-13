package com.fallingnight.chat.storage.s3;

public final class S3ProfileImageObjectDeletionException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public S3ProfileImageObjectDeletionException(String message, Throwable cause) {
        super(message, cause);
    }
}
