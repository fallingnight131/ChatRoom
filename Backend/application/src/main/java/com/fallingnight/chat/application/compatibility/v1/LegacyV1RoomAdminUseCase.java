package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomAdminUseCase {
    LegacyV1RoomAdminResult change(LegacyV1RoomAdminCommand command);
}
