package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;

/** Minimal durable V1 message state needed to validate cursor migration. */
public record V1MessageCursorRow(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        long legacySenderUserId,
        long creationSequence,
        Long mutationSequence,
        boolean recalled,
        Instant createdAt) {}
