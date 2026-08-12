package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;

/** Complete V1 room settings projection for pre-write planning. */
public record V1RoomRow(long legacyRoomId, String name, long creatorUserId,
        long maxFileSize, long totalFileSpace, int maxFileCount,
        int maxMembers, Instant createdAt) {
    public V1RoomRow(long legacyRoomId, String name, long creatorUserId,
            int maxMembers, Instant createdAt) {
        this(legacyRoomId, name, creatorUserId,
                10_737_418_240L, 10_737_418_240L, 1500, maxMembers, createdAt);
    }
}
