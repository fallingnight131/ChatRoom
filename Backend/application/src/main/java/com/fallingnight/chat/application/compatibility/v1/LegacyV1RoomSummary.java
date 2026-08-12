package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** Existing V1 room-list fields derived from canonical conversation state. */
public record LegacyV1RoomSummary(
        long roomId,
        String roomName,
        long unread,
        boolean administrator) {
    public LegacyV1RoomSummary {
        if (roomId <= 0 || roomId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("roomId must fit the V1 signed integer range");
        }
        Objects.requireNonNull(roomName, "roomName");
        if (roomName.isBlank() || roomName.length() > 100) {
            throw new IllegalArgumentException("roomName must contain 1..100 characters");
        }
        if (unread < 0) {
            throw new IllegalArgumentException("unread must not be negative");
        }
    }
}
