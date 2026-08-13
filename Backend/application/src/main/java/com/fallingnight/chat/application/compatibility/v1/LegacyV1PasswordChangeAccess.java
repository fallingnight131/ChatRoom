package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.StoredCredential;
import java.time.Instant;
import java.util.Objects;

public sealed interface LegacyV1PasswordChangeAccess {
    record Candidate(StoredCredential credential, Instant passwordChangedAt)
            implements LegacyV1PasswordChangeAccess {
        public Candidate {
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(passwordChangedAt, "passwordChangedAt");
        }
    }
    enum Rejected implements LegacyV1PasswordChangeAccess { SESSION_INVALID }
}
