package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomFileDeletionPort {
    LegacyV1RoomFileDeletionResult delete(LegacyV1RoomFileDeletionIntent intent);
}
