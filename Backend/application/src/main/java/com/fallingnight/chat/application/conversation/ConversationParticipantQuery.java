package com.fallingnight.chat.application.conversation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authenticated, bounded active-participant directory query. */
public record ConversationParticipantQuery(
        UUID conversationId, UUID requesterAccountId, Optional<UUID> afterAccountId, int limit) {
    public static final int MAX_LIMIT = 100;

    public ConversationParticipantQuery {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(requesterAccountId, "requesterAccountId");
        afterAccountId = Objects.requireNonNull(afterAccountId, "afterAccountId");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in 1..100");
        }
    }
}
