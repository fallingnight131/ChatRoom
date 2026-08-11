package com.fallingnight.chat.application.messaging;

import java.util.Objects;
import java.util.UUID;

/** Authenticated forward-only sequence cursor query. */
public record MessageHistoryQuery(
        UUID conversationId, UUID accountId, long afterSequence, int limit) {
    public MessageHistoryQuery {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(accountId, "accountId");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must be nonnegative");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be in 1..100");
        }
    }
}
