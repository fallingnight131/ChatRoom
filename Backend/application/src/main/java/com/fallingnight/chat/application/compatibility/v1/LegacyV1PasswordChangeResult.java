package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;

public sealed interface LegacyV1PasswordChangeResult {
    record Changed(boolean changed, int otherSessionsRevoked, Instant changedAt)
            implements LegacyV1PasswordChangeResult {
        public Changed {
            Objects.requireNonNull(changedAt, "changedAt");
            if (otherSessionsRevoked < 0 || (!changed && otherSessionsRevoked != 0))
                throw new IllegalArgumentException("invalid password change result");
        }
    }
    enum Rejected implements LegacyV1PasswordChangeResult {
        INVALID_INPUT,
        CURRENT_PASSWORD_INCORRECT,
        SESSION_INVALID,
        CONCURRENT_CHANGE
    }
}
