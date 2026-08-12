package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomJoinUseCase {
    LegacyV1RoomJoinResult join(LegacyV1RoomJoinCommand command);
}
