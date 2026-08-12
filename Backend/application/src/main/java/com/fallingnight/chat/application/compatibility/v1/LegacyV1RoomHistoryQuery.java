package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Raw V1 room-history intent; authenticated account identity is server-bound. */
public record LegacyV1RoomHistoryQuery(
        UUID accountId,
        long legacyRoomId,
        int limit,
        long beforeEpochMillis,
        Long afterSequence) { }
