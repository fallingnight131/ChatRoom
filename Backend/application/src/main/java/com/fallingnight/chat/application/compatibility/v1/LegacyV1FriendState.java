package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Canonical authorized friend state before V1 identifier and presence projection. */
public record LegacyV1FriendState(
        UUID conversationId,
        UUID peerAccountId,
        String username,
        String displayName,
        long unread,
        long peerLastReadMessageId) {
    public LegacyV1FriendState {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(peerAccountId, "peerAccountId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        if (username.isBlank() || username.length() > 128
                || displayName.isBlank() || displayName.length() > 100) {
            throw new IllegalArgumentException("friend identity is outside V1 bounds");
        }
        if (unread < 0 || peerLastReadMessageId < 0) {
            throw new IllegalArgumentException("friend counters must be nonnegative");
        }
    }
}
