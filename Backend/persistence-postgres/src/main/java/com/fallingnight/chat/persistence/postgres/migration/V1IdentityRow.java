package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;

/** Raw V1 identity projection read from the authoritative SQLite database. */
public record V1IdentityRow(
        long legacyId,
        String username,
        String displayName,
        String passwordHash,
        String legacySalt,
        Instant createdAt) {
}
