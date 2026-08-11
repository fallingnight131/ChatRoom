package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Temporary V1 numeric identity paired with its canonical V2 account. */
public record LegacyV1AccountIdentity(long legacyUserId, UUID accountId) {
    public LegacyV1AccountIdentity {
        if (legacyUserId <= 0) {
            throw new IllegalArgumentException("legacyUserId must be positive");
        }
        Objects.requireNonNull(accountId, "accountId");
    }
}
