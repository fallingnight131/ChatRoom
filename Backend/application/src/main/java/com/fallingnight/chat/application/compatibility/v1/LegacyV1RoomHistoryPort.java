package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1RoomHistoryPort {
    LegacyV1RoomHistoryResult read(LegacyV1RoomHistoryQuery query);
}
