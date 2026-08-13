package com.fallingnight.chat.application.routing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConversationRouteRegistration(
        UUID conversationId, long caughtUpThroughSequence, Instant expiresAt) {
    public ConversationRouteRegistration {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (caughtUpThroughSequence < 0) {
            throw new IllegalArgumentException("caughtUpThroughSequence must not be negative");
        }
    }
}
