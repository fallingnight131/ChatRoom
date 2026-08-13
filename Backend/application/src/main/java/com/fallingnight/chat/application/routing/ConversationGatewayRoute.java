package com.fallingnight.chat.application.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Expiring route published only after this gateway caught up the conversation. */
public record ConversationGatewayRoute(
        UUID gatewayId,
        UUID conversationId,
        long caughtUpThroughSequence,
        Instant publishedAt,
        Instant expiresAt) {
    public ConversationGatewayRoute {
        Objects.requireNonNull(gatewayId, "gatewayId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (caughtUpThroughSequence < 0 || !expiresAt.isAfter(publishedAt)
                || Duration.between(publishedAt, expiresAt)
                    .compareTo(GatewayRouteLease.MAX_LEASE) > 0) {
            throw new IllegalArgumentException("invalid conversation gateway route");
        }
    }
}
