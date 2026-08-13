package com.fallingnight.chat.persistence.postgres.migration;

import java.util.Objects;

/** Non-secret target-preview issue. */
public record V1ProfileImageImportIssue(
        V1ProfileImageImportEntry.Kind kind, long legacyId, String code) {
    public V1ProfileImageImportIssue {
        Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(code, "code");
        if (legacyId <= 0 || !code.matches("[A-Z_]{3,64}"))
            throw new IllegalArgumentException("invalid avatar import issue");
    }
}
