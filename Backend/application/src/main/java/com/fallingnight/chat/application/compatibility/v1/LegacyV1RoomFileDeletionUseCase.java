package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomFileDeletionUseCase {
    LegacyV1RoomFileDeletionResult delete(LegacyV1RoomFileDeletionCommand command);
}
