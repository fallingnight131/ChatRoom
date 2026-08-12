package com.fallingnight.chat.application.compatibility.v1;

public sealed interface LegacyV1RoomFilesResult {
    record Read(long legacyRoomId, LegacyV1RoomFiles files)
            implements LegacyV1RoomFilesResult {
        public Read {
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE || files == null) {
                throw new IllegalArgumentException("invalid V1 room files result");
            }
        }
    }
    enum Rejected implements LegacyV1RoomFilesResult {
        INVALID_INPUT,
        ROOM_ADMIN_REQUIRED
    }
}
