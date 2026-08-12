package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Internal canonical identity plus bounded V1 room-search projection. */
public record LegacyV1RoomSearchEntry(UUID conversationId, long legacyRoomId,
        String roomName, long legacyCreatorId, int memberCount) {
    public LegacyV1RoomSearchEntry {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(roomName, "roomName");
        if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                || legacyCreatorId <= 0 || legacyCreatorId > Integer.MAX_VALUE
                || roomName.isBlank() || memberCount < 0) {
            throw new IllegalArgumentException("V1 room search entry");
        }
    }
}
