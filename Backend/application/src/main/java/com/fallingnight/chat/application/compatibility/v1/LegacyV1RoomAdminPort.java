package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomAdminPort {
    LegacyV1RoomAdminResult change(LegacyV1RoomAdminCommand command);
}
