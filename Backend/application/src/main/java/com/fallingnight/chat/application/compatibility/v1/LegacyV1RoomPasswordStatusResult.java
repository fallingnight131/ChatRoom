package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomPasswordStatusResult {
    record Authorized(UUID conversationId, long legacyRoomId,
            boolean hasPassword, Instant updatedAt) implements LegacyV1RoomPasswordStatusResult {
        public Authorized {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid V1 room password status");
        }
    }
    enum Rejected implements LegacyV1RoomPasswordStatusResult {
        INVALID_INPUT,
        ROOM_ADMIN_REQUIRED
    }
}
