package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Validated PostgreSQL account material; never serialize this record to logs. */
public record PlannedIdentityAccount(
        long legacyId,
        UUID accountId,
        String usernameKey,
        String displayName,
        String passwordHash,
        ImportedCredentialScheme credentialScheme,
        String legacyPasswordSalt,
        Instant createdAt) {
    public PlannedIdentityAccount {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(usernameKey, "usernameKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(credentialScheme, "credentialScheme");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
