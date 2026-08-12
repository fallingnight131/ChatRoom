package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomCreationUseCase {
    LegacyV1RoomCreationResult create(LegacyV1RoomCreationCommand command);
}
