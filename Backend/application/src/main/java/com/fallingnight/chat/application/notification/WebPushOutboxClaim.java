package com.fallingnight.chat.application.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One fenced lease over a payload-free Web Push notification intent. */
public record WebPushOutboxClaim(
        WebPushNotificationIntent intent,
        UUID claimId,
        UUID claimOwner,
        Instant claimedAt,
        Instant claimExpiresAt,
        int attemptCount) {
    public WebPushOutboxClaim {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(claimOwner, "claimOwner");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
        if (attemptCount < 1
                || claimedAt.isBefore(intent.committedAt())
                || !claimExpiresAt.isAfter(claimedAt)
                || claimExpiresAt.isAfter(intent.expiresAt())) {
            throw new IllegalArgumentException("invalid Web Push outbox claim");
        }
    }
}
