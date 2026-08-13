package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

/** Server-bound actor and bounded V1 room-file deletion command. */
public record LegacyV1RoomFileDeletionCommand(
        UUID actorAccountId,
        long legacyRoomId,
        String clientOperationId,
        List<Long> legacyFileIds) { }
