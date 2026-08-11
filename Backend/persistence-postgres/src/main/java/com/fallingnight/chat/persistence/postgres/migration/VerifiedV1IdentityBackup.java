package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;
import java.util.Objects;

/** Portable proof fields persisted beside an operator's backup artifact. */
public record VerifiedV1IdentityBackup(
        String sourceFingerprintSha256,
        String backupFileSha256,
        int identityRows,
        long backupBytes,
        Instant createdAt) {
    public VerifiedV1IdentityBackup {
        Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        Objects.requireNonNull(backupFileSha256, "backupFileSha256");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
