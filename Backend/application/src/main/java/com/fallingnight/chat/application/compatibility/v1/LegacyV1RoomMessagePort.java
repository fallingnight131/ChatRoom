package com.fallingnight.chat.application.compatibility.v1;
@FunctionalInterface public interface LegacyV1RoomMessagePort {
    LegacyV1RoomMessageResult submit(LegacyV1RoomMessageCommand command);
}
