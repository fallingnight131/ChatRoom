package com.fallingnight.chat.persistence.postgres.migration;

import java.util.Objects;

/** Non-secret import issue keyed only by the legacy numeric row identity. */
public record IdentityImportIssue(long legacyId, String code, String safeMessage) {
    public IdentityImportIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(safeMessage, "safeMessage");
    }
}
