package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** Exact fields consumed by supported V1 Web and Windows friend lists. */
public record LegacyV1FriendSummary(
        long friendshipId,
        long friendId,
        String username,
        String displayName,
        boolean online,
        long unread,
        long peerLastReadMessageId) {
    public LegacyV1FriendSummary {
        if (friendshipId <= 0 || friendshipId > Integer.MAX_VALUE
                || friendId <= 0 || friendId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("V1 friend identifiers must fit signed integers");
        }
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        if (unread < 0 || peerLastReadMessageId < 0
                || peerLastReadMessageId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("V1 friend counters are outside bounds");
        }
    }
}
