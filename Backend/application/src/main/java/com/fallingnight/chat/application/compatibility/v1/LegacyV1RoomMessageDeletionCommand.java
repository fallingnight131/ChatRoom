package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

/** Server-bound actor and raw V1 administrative room-message deletion command. */
public record LegacyV1RoomMessageDeletionCommand(
        UUID actorAccountId,
        long legacyRoomId,
        String clientOperationId,
        String mode,
        List<Long> legacyMessageIds,
        long cutoffEpochMillis) { }
