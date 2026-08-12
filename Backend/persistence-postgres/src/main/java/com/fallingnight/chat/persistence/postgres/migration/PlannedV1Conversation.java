package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Validated canonical conversation identity ready for target comparison. */
public record PlannedV1Conversation(
        LegacyV1ConversationKind legacyKind,
        long legacyId,
        UUID conversationId,
        String groupTitle,
        UUID firstAccountId,
        UUID secondAccountId,
        Instant createdAt) {
    public PlannedV1Conversation {
        Objects.requireNonNull(legacyKind, "legacyKind");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (legacyKind == LegacyV1ConversationKind.ROOM) {
            Objects.requireNonNull(groupTitle, "groupTitle");
            if (firstAccountId != null || secondAccountId != null) {
                throw new IllegalArgumentException("room cannot carry direct pair");
            }
        } else {
            if (groupTitle != null) {
                throw new IllegalArgumentException("friendship cannot carry group title");
            }
            Objects.requireNonNull(firstAccountId, "firstAccountId");
            Objects.requireNonNull(secondAccountId, "secondAccountId");
            if (firstAccountId.toString().compareTo(secondAccountId.toString()) > 0) {
                throw new IllegalArgumentException("direct pair must be canonical");
            }
        }
    }
}
