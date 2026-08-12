package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.util.UUID;

/** Importable V1 text-like content with deterministic V2 identities. */
public record PlannedV1MessagePayload(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        UUID messageId,
        String targetClientMessageId,
        int targetContentType,
        String targetText,
        boolean historicalContentAvailable) {}
