package com.fallingnight.chat.persistence.postgres.migration;

import java.util.Objects;

/** Safe issue metadata; never includes usernames or source paths. */
public record V1ContactRequestImportIssue(long legacyRequestId, String code, String message) {
    public V1ContactRequestImportIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
