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
        if (!sourceFingerprintSha256.matches("[0-9a-f]{64}")
                || !backupFileSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("backup proof hashes must be lowercase SHA-256");
        }
        if (identityRows <= 0 || backupBytes <= 0) {
            throw new IllegalArgumentException("backup proof counts must be positive");
        }
    }
}
