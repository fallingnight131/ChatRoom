package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;
import java.util.UUID;

/** Complete text-like target message row plus retained V1 recall cursor state. */
public record PlannedV1HistoricalMessage(
        UUID messageId,
        UUID conversationId,
        long creationSequence,
        Long mutationSequence,
        UUID senderAccountId,
        UUID senderDeviceId,
        String clientMessageId,
        int contentType,
        String text,
        boolean recalled,
        boolean historicalContentAvailable,
        Instant acceptedAt) {}
