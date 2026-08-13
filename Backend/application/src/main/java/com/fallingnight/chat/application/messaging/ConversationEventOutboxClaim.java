package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One payload-free, fenced lease over a committed conversation event. */
public record ConversationEventOutboxClaim(
        UUID eventId,
        UUID conversationId,
        long conversationSequence,
        UUID claimId,
        UUID claimOwner,
        Instant claimedAt,
        Instant claimExpiresAt,
        int attemptCount) {
    public ConversationEventOutboxClaim {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(claimOwner, "claimOwner");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
        if (conversationSequence < 1 || attemptCount < 1
                || !claimExpiresAt.isAfter(claimedAt)) {
            throw new IllegalArgumentException("invalid conversation event outbox claim");
        }
    }
}
