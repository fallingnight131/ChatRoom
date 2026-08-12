package com.fallingnight.chat.persistence.postgres.migration;

import java.util.UUID;

/** Target sequence state preserving the complete allocated V1 cursor range. */
public record PlannedV1ConversationCursor(
        UUID conversationId,
        long legacyLastSequence,
        long targetNextSequence) {}
