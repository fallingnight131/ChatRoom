package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomPasswordHashPort {
    LegacyV1RoomPasswordEncoding hash(byte[] passwordUtf8);
}
