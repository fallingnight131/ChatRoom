package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1RoomPasswordUpdateUseCase {
    LegacyV1RoomPasswordUpdateResult update(LegacyV1RoomPasswordCommand command);
}
