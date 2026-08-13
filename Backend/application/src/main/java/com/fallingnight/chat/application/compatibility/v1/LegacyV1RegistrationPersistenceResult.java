package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.StoredCredential;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

public sealed interface LegacyV1RegistrationPersistenceResult {
    record Created(UUID accountId, long legacyUserId, Instant createdAt)
            implements LegacyV1RegistrationPersistenceResult {
        public Created {
            Objects.requireNonNull(accountId, "accountId"); Objects.requireNonNull(createdAt, "createdAt");
            if (legacyUserId <= 0 || legacyUserId > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid created V1 user ID");
        }
    }
    record Existing(UUID accountId, OptionalLong legacyUserId, String username,
            String displayName, StoredCredential credential, Instant createdAt)
            implements LegacyV1RegistrationPersistenceResult {
        public Existing {
            Objects.requireNonNull(accountId, "accountId");
            legacyUserId = Objects.requireNonNull(legacyUserId, "legacyUserId");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(credential, "credential");
            Objects.requireNonNull(createdAt, "createdAt");
            if (legacyUserId.isPresent() && (legacyUserId.getAsLong() <= 0
                    || legacyUserId.getAsLong() > Integer.MAX_VALUE))
                throw new IllegalArgumentException("invalid existing V1 user ID");
        }
    }
    enum Rejected implements LegacyV1RegistrationPersistenceResult { ID_CAPACITY_EXHAUSTED }
}
