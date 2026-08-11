package com.fallingnight.chat.application.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Authorized conversation projection; peer/member lists remain separate. */
public record ConversationSummary(
        UUID conversationId,
        ConversationKind kind,
        String displayName,
        ConversationRole role,
        long latestSequence,
        long lastReadSequence,
        Instant updatedAt) {
    public ConversationSummary {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (displayName.isBlank() || displayName.length() > 100) {
            throw new IllegalArgumentException("displayName must contain 1..100 characters");
        }
        if (latestSequence < 0 || lastReadSequence < 0 || lastReadSequence > latestSequence) {
            throw new IllegalArgumentException("conversation sequence range is invalid");
        }
    }
}
