package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomKickUseCase {
    LegacyV1RoomKickResult kick(LegacyV1RoomKickCommand command);
}
