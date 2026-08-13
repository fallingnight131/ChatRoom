package com.fallingnight.chat.migration.profile;

public final class V1ProfileImageExportException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public V1ProfileImageExportException(String message) { super(message); }
    public V1ProfileImageExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
