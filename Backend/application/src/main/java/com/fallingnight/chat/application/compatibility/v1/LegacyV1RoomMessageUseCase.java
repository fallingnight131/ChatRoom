package com.fallingnight.chat.application.compatibility.v1;
@FunctionalInterface public interface LegacyV1RoomMessageUseCase {
    LegacyV1RoomMessageResult submit(LegacyV1RoomMessageCommand command);
}
