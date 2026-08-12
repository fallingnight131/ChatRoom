package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomRecallPort {
    LegacyV1RoomRecallResult recall(LegacyV1RoomRecallCommand command);
}
