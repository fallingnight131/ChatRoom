package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomReadPort {
    LegacyV1RoomReadResult markRead(LegacyV1RoomReadCommand command);
}
