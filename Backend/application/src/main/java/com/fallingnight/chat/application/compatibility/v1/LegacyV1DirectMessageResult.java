package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1DirectMessageResult {
    record Accepted(
            boolean duplicate,
            long legacyFriendshipId,
            long legacyMessageId,
            long sequence,
            Instant acceptedAt,
            UUID targetAccountId,
            String targetUsername)
            implements LegacyV1DirectMessageResult {
        public Accepted {
            if (legacyFriendshipId <= 0 || legacyFriendshipId > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("legacyFriendshipId");
            }
            if (legacyMessageId <= 0 || legacyMessageId > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("legacyMessageId");
            }
            if (sequence <= 0) throw new IllegalArgumentException("sequence");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(targetAccountId, "targetAccountId");
            Objects.requireNonNull(targetUsername, "targetUsername");
        }
    }

    enum Rejected implements LegacyV1DirectMessageResult {
        FRIENDSHIP_ACCESS_DENIED,
        INVALID_MESSAGE,
        INVALID_CLIENT_MESSAGE_ID,
        CLIENT_MESSAGE_ID_CONFLICT
    }
}
