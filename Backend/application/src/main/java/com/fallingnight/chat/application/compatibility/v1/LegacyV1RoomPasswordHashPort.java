package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomPasswordHashPort {
    String hash(byte[] passwordUtf8);
}
