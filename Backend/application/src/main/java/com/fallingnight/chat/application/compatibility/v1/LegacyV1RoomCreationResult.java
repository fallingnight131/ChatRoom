package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomCreationResult {
    record Created(UUID conversationId, long legacyRoomId, String roomName,
            UUID creatorAccountId, boolean duplicate) implements LegacyV1RoomCreationResult {
        public Created {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(roomName, "roomName");
            Objects.requireNonNull(creatorAccountId, "creatorAccountId");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE || roomName.isBlank()) {
                throw new IllegalArgumentException("V1 room creation result");
            }
        }
    }
    enum Rejected implements LegacyV1RoomCreationResult {
        INVALID_INPUT,
        CREATION_DENIED,
        CLIENT_REQUEST_ID_CONFLICT
    }
}
