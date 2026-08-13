package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;

public sealed interface LegacyV1RegistrationResult {
    record Registered(long legacyUserId, String username, String displayName,
            boolean duplicate, Instant createdAt) implements LegacyV1RegistrationResult {
        public Registered {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(createdAt, "createdAt");
            if (legacyUserId <= 0 || legacyUserId > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid registered V1 user ID");
        }
    }
    enum Rejected implements LegacyV1RegistrationResult {
        INVALID_INPUT,
        USERNAME_TAKEN,
        REGISTRATION_UNAVAILABLE
    }
}
