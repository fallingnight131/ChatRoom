package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomReadUseCase {
    LegacyV1RoomReadResult markRead(LegacyV1RoomReadCommand command);
}
