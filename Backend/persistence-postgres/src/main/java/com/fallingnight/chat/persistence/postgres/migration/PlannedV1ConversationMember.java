package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Validated V2 membership retaining the V1 read-message ID for later translation. */
public record PlannedV1ConversationMember(
        UUID conversationId,
        UUID accountId,
        String role,
        Instant joinedAt,
        long legacyLastReadMessageId) {
    public PlannedV1ConversationMember {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(accountId, "accountId");
        if (!"OWNER".equals(role) && !"ADMIN".equals(role) && !"MEMBER".equals(role)) {
            throw new IllegalArgumentException("unsupported planned role");
        }
        Objects.requireNonNull(joinedAt, "joinedAt");
        if (legacyLastReadMessageId < 0) {
            throw new IllegalArgumentException("legacyLastReadMessageId must be nonnegative");
        }
    }
}
