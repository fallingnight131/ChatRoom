package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

/** Validated canonical selected-file deletion passed to persistence. */
public record LegacyV1RoomFileDeletionIntent(
        UUID actorAccountId,
        long legacyRoomId,
        String clientOperationId,
        String commandFingerprint,
        List<Long> legacyFileIds) { }
