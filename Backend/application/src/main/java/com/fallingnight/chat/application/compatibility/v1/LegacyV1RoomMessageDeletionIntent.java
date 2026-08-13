package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

/** Validated canonical administrative deletion passed to atomic persistence. */
public record LegacyV1RoomMessageDeletionIntent(
        UUID actorAccountId,
        long legacyRoomId,
        String clientOperationId,
        String commandFingerprint,
        LegacyV1RoomMessageDeletionMode mode,
        List<Long> legacyMessageIds,
        long cutoffEpochMillis) { }
