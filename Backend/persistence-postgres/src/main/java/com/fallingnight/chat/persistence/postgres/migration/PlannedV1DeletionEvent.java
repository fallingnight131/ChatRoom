package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;
import java.util.UUID;

/** Complete typed target row for one durable V1 room deletion event. */
public record PlannedV1DeletionEvent(
        long legacyEventId,
        long legacyRoomId,
        UUID conversationId,
        long conversationSequence,
        UUID actorAccountId,
        String operatorName,
        String clientOperationId,
        String commandFingerprint,
        String mode,
        String messageIdsJson,
        String fileIdsJson,
        long cutoffEpochMs,
        int deletedCount,
        Instant occurredAt) {}
