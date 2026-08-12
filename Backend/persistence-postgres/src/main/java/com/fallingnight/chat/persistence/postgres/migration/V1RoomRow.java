package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;

/** Minimal V1 room projection for pre-write conversation planning. */
public record V1RoomRow(long legacyRoomId, String name, long creatorUserId, Instant createdAt) {}
