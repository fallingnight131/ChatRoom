package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Server-bound actor plus the V1 room/message identity accepted from the wire. */
public record LegacyV1RoomRecallCommand(
        UUID actorAccountId,
        long legacyRoomId,
        long legacyMessageId) { }
