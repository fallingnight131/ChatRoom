package com.fallingnight.chat.application.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable descending directory cursor; timestamp and UUID are one indivisible key. */
public record ConversationDirectoryCursor(Instant updatedAt, UUID conversationId) {
    public ConversationDirectoryCursor {
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(conversationId, "conversationId");
    }
}
