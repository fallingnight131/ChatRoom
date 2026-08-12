package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;

/** Minimal V1 friendship projection with both untranslated read pointers. */
public record V1FriendshipRow(
        long legacyFriendshipId,
        long firstUserId,
        long secondUserId,
        Instant createdAt,
        long firstLastReadMessageId,
        long secondLastReadMessageId) {}
