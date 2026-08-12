package com.fallingnight.chat.application.compatibility.v1;

public sealed interface LegacyV1RoomSettingsResult {
    record Read(long legacyRoomId, LegacyV1RoomSettings settings)
            implements LegacyV1RoomSettingsResult {
        public Read {
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("V1 room settings room ID");
            }
            if (settings == null) throw new NullPointerException("settings");
        }
    }
    enum Rejected implements LegacyV1RoomSettingsResult {
        INVALID_INPUT, ROOM_ACCESS_DENIED
    }
}
