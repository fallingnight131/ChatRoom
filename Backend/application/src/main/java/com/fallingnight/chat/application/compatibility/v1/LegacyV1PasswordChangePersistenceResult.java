package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;

public sealed interface LegacyV1PasswordChangePersistenceResult {
    record Updated(int otherSessionsRevoked, Instant changedAt)
            implements LegacyV1PasswordChangePersistenceResult {
        public Updated {
            Objects.requireNonNull(changedAt, "changedAt");
            if (otherSessionsRevoked < 0)
                throw new IllegalArgumentException("negative revoked session count");
        }
    }
    enum Rejected implements LegacyV1PasswordChangePersistenceResult {
        SESSION_INVALID,
        CONCURRENT_CHANGE
    }
}
