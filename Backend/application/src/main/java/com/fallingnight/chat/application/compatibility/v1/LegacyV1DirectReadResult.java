package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1DirectReadResult {
    record Marked(UUID conversationId, long legacyFriendshipId,
            long previousSequence, long lastReadSequence, boolean changed,
            long legacyLastReadMessageId, UUID targetAccountId, String targetUsername)
            implements LegacyV1DirectReadResult {
        public Marked {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(targetAccountId, "targetAccountId");
            Objects.requireNonNull(targetUsername, "targetUsername");
            if (legacyFriendshipId <= 0 || legacyFriendshipId > Integer.MAX_VALUE
                    || previousSequence < 0 || lastReadSequence < previousSequence
                    || changed != (lastReadSequence > previousSequence)
                    || legacyLastReadMessageId < 0
                    || legacyLastReadMessageId > Integer.MAX_VALUE
                    || targetUsername.isBlank()) {
                throw new IllegalArgumentException("direct read cursor result");
            }
        }
    }

    enum Rejected implements LegacyV1DirectReadResult {
        FRIENDSHIP_ACCESS_DENIED,
        INVALID_FRIENDSHIP_ID
    }
}
