package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One validated pending request ready for exact target comparison. */
public record PlannedV1ContactRequest(
        long legacyRequestId,
        UUID requestId,
        UUID requesterAccountId,
        UUID recipientAccountId,
        Instant createdAt) {
    public PlannedV1ContactRequest {
        if (legacyRequestId <= 0) throw new IllegalArgumentException("legacyRequestId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(requesterAccountId, "requesterAccountId");
        Objects.requireNonNull(recipientAccountId, "recipientAccountId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
