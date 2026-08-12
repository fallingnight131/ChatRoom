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
        Long maxFileSize,
        Long totalFileSpace,
        Integer maxFileCount,
        Integer maxMembers,
        UUID firstAccountId,
        UUID secondAccountId,
        Instant createdAt) {
    public PlannedV1Conversation {
        Objects.requireNonNull(legacyKind, "legacyKind");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (legacyKind == LegacyV1ConversationKind.ROOM) {
            Objects.requireNonNull(groupTitle, "groupTitle");
            Objects.requireNonNull(maxFileSize, "maxFileSize");
            Objects.requireNonNull(totalFileSpace, "totalFileSpace");
            Objects.requireNonNull(maxFileCount, "maxFileCount");
            Objects.requireNonNull(maxMembers, "maxMembers");
            if (maxFileSize < 1 || maxFileSize > 9_007_199_254_740_991L
                    || totalFileSpace < maxFileSize
                    || totalFileSpace > 9_007_199_254_740_991L
                    || maxFileCount < 1) {
                throw new IllegalArgumentException("room resource limits are unsupported");
            }
            if (maxMembers < 1 || maxMembers > 1_000_000) {
                throw new IllegalArgumentException("room member limit is unsupported");
            }
            if (firstAccountId != null || secondAccountId != null) {
                throw new IllegalArgumentException("room cannot carry direct pair");
            }
        } else {
            if (groupTitle != null || maxFileSize != null || totalFileSpace != null
                    || maxFileCount != null || maxMembers != null) {
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
