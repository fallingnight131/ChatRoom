package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;
import java.util.UUID;

/** Complete text-like target message row plus retained V1 recall cursor state. */
public record PlannedV1HistoricalMessage(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        UUID messageId,
        UUID conversationId,
        long creationSequence,
        Long mutationSequence,
        UUID senderAccountId,
        UUID senderDeviceId,
        String clientMessageId,
        int contentType,
        String legacyContentType,
        String text,
        boolean recalled,
        boolean historicalContentAvailable,
        Instant acceptedAt) {}
