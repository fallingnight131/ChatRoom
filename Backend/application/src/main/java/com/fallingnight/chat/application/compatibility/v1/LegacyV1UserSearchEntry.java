package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Durable V1-compatible search projection before ephemeral presence is joined. */
public record LegacyV1UserSearchEntry(
        UUID accountId,
        long legacyUserId,
        String username,
        String displayName) {
    public LegacyV1UserSearchEntry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        if (legacyUserId <= 0 || legacyUserId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("legacyUserId outside V1 range");
        }
        if (username.isBlank() || displayName.isBlank()) {
            throw new IllegalArgumentException("search identity fields must not be blank");
        }
    }
}
