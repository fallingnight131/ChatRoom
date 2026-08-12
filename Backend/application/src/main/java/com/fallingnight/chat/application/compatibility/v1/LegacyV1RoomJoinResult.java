package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomJoinResult {
    enum Role { OWNER, ADMIN, MEMBER }

    record Joined(UUID conversationId, long legacyRoomId, String roomName,
            UUID actorAccountId, Role role, boolean newJoin) implements LegacyV1RoomJoinResult {
        public Joined {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(roomName, "roomName");
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            Objects.requireNonNull(role, "role");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE || roomName.isBlank()) {
                throw new IllegalArgumentException("V1 room join result");
            }
        }
    }

    enum Rejected implements LegacyV1RoomJoinResult {
        INVALID_INPUT,
        NOT_FOUND,
        PASSWORD_REQUIRED,
        INVALID_PASSWORD,
        ROOM_FULL,
        JOIN_DENIED,
        ACCESS_CHANGED
    }
}
