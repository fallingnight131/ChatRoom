package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomKickPort {
    LegacyV1RoomKickResult kick(LegacyV1RoomKickCommand command);
}
