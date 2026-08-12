package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomCreationPort {
    LegacyV1RoomCreationResult create(LegacyV1RoomCreationIntent intent);
}
