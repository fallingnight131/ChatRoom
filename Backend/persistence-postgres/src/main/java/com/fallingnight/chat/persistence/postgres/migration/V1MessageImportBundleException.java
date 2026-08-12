package com.fallingnight.chat.persistence.postgres.migration;

/** Safe failure when independently verified V1 message inputs do not compose. */
public final class V1MessageImportBundleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public V1MessageImportBundleException(String message) {
        super(message);
    }
}
