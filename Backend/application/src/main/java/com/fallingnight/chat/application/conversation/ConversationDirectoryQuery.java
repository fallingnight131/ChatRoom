package com.fallingnight.chat.application.conversation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded authenticated account directory query. */
public record ConversationDirectoryQuery(
        UUID accountId,
        Optional<ConversationDirectoryCursor> after,
        int limit) {
    public static final int MAX_LIMIT = 100;

    public ConversationDirectoryQuery {
        Objects.requireNonNull(accountId, "accountId");
        after = Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in 1..100");
        }
    }
}
