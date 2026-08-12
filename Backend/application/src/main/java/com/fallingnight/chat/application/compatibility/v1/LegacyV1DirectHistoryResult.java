package com.fallingnight.chat.application.compatibility.v1;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface LegacyV1DirectHistoryResult {
    record Page(long legacyFriendshipId, String targetUsername,
            boolean sequenceMode, List<LegacyV1DirectHistoryMessage> messages,
            long nextSequence, long lastSequence, boolean hasMore)
            implements LegacyV1DirectHistoryResult {
        public Page {
            if (legacyFriendshipId <= 0 || legacyFriendshipId > Integer.MAX_VALUE
                    || nextSequence < 0 || lastSequence < 0 || nextSequence > lastSequence) {
                throw new IllegalArgumentException("direct history page identity");
            }
            Objects.requireNonNull(targetUsername, "targetUsername");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            Set<Long> ids = new HashSet<>();
            long previous = -1;
            for (LegacyV1DirectHistoryMessage message : messages) {
                if (!ids.add(message.legacyMessageId())) {
                    throw new IllegalArgumentException("duplicate direct history message");
                }
                long order = sequenceMode ? message.syncSequence() : message.sequence();
                if (order <= previous || order > lastSequence) {
                    throw new IllegalArgumentException("unordered direct history page");
                }
                previous = order;
            }
            if (sequenceMode && previous > nextSequence) {
                throw new IllegalArgumentException("history exceeds continuation cursor");
            }
            if (!sequenceMode && (hasMore || nextSequence != lastSequence)) {
                throw new IllegalArgumentException("latest history has continuation metadata");
            }
        }
    }

    enum Rejected implements LegacyV1DirectHistoryResult {
        FRIENDSHIP_ACCESS_DENIED,
        INVALID_SEQUENCE_CURSOR,
        INVALID_REQUEST
    }
}
