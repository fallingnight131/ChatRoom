package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomPasswordUpdateResult {
    record Updated(UUID conversationId, long legacyRoomId, boolean hasPassword,
            boolean changed, Instant updatedAt) implements LegacyV1RoomPasswordUpdateResult {
        public Updated {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid V1 room password update");
        }
    }
    enum Rejected implements LegacyV1RoomPasswordUpdateResult {
        INVALID_INPUT,
        ROOM_ADMIN_REQUIRED
    }
}
