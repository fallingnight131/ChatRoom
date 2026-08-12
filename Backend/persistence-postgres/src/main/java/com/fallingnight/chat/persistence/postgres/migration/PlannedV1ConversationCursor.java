package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.util.UUID;

/** Target sequence state preserving the complete allocated V1 cursor range. */
public record PlannedV1ConversationCursor(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        UUID conversationId,
        long legacyLastSequence,
        long targetNextSequence) {}
