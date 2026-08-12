package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;

/** Minimal V1 room membership projection, retaining the untranslated read pointer. */
public record V1RoomMembershipRow(
        long legacyRoomId,
        long legacyUserId,
        Instant joinedAt,
        long legacyLastReadMessageId) {}
