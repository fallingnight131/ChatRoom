package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;

/** Minimal V1 room and admission-policy projection for pre-write planning. */
public record V1RoomRow(long legacyRoomId, String name, long creatorUserId,
        int maxMembers, Instant createdAt) {}
