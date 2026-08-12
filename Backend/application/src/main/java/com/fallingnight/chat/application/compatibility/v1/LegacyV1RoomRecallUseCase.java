package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomRecallUseCase {
    LegacyV1RoomRecallResult recall(LegacyV1RoomRecallCommand command);
}
