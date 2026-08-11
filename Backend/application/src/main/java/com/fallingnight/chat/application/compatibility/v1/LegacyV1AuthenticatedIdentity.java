package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Server-bound identity established for one V1 compatibility connection. */
public record LegacyV1AuthenticatedIdentity(
        long legacyUserId,
        UUID accountId,
        UUID deviceId,
        UUID sessionId,
        Instant expiresAt,
        String username,
        String displayName,
        boolean credentialUpgradePending) {
    public LegacyV1AuthenticatedIdentity {
        if (legacyUserId <= 0) {
            throw new IllegalArgumentException("legacyUserId must be positive");
        }
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        Objects.requireNonNull(displayName, "displayName");
    }
}
