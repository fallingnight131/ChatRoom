package com.fallingnight.chat.persistence.postgres.migration;

/** Safe attachment import verification or target reconciliation failure. */
public final class V1AttachmentImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1AttachmentImportException(String message) {
        super(message);
    }

    V1AttachmentImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
