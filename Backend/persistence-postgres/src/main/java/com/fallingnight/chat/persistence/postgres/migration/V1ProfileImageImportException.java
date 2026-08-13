package com.fallingnight.chat.persistence.postgres.migration;

public final class V1ProfileImageImportException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    public V1ProfileImageImportException(String message) { super(message); }
    public V1ProfileImageImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
