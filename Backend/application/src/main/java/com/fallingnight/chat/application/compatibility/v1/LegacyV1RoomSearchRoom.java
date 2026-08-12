package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** UUID-free V1 room-search wire projection. */
public record LegacyV1RoomSearchRoom(long roomId, String roomName,
        long creatorId, int memberCount) {
    public LegacyV1RoomSearchRoom {
        Objects.requireNonNull(roomName, "roomName");
        if (roomId <= 0 || roomId > Integer.MAX_VALUE
                || creatorId <= 0 || creatorId > Integer.MAX_VALUE
                || roomName.isBlank() || memberCount < 0) {
            throw new IllegalArgumentException("V1 room search result");
        }
    }
}
