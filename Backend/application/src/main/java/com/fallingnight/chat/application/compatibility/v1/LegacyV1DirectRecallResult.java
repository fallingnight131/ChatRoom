package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1DirectRecallResult {
    record Recalled(boolean duplicate, long legacyFriendshipId, long legacyMessageId,
            long mutationSequence, Instant occurredAt, UUID targetAccountId,
            String targetUsername) implements LegacyV1DirectRecallResult {
        public Recalled {
            if (legacyFriendshipId <= 0 || legacyFriendshipId > Integer.MAX_VALUE
                    || legacyMessageId <= 0 || legacyMessageId > Integer.MAX_VALUE
                    || mutationSequence <= 0) {
                throw new IllegalArgumentException("direct recall identity");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(targetAccountId, "targetAccountId");
            Objects.requireNonNull(targetUsername, "targetUsername");
            if (targetUsername.isBlank()) throw new IllegalArgumentException("targetUsername");
        }
    }

    enum Rejected implements LegacyV1DirectRecallResult {
        RECALL_DENIED,
        INVALID_MESSAGE_ID
    }
}
