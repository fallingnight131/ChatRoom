package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;

/** Minimal V1 administrative deletion event state in the room cursor namespace. */
public record V1RoomDeletionCursorRow(
        long legacyEventId,
        long legacyRoomId,
        long legacyOperatorUserId,
        long sequence,
        Instant createdAt) {}
