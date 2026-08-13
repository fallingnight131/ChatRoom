package com.fallingnight.chat.application.routing;

import java.util.Objects;
import java.util.UUID;

/** Payload-free live-delivery hint; consumers repair content from SQL sequence truth. */
public record GatewayLiveEventHint(
        UUID targetGatewayId,
        UUID eventId,
        UUID conversationId,
        long conversationSequence) {
    public GatewayLiveEventHint {
        Objects.requireNonNull(targetGatewayId, "targetGatewayId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(conversationId, "conversationId");
        if (conversationSequence < 1) {
            throw new IllegalArgumentException("conversationSequence must be positive");
        }
    }
}
