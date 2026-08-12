package com.fallingnight.chat.storage.s3;

/** Provider failure or violated S3 attachment integrity contract. */
public final class AttachmentObjectStoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AttachmentObjectStoreException(String message) {
        super(message);
    }

    public AttachmentObjectStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
