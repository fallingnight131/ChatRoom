package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;

/** V1 durable shared cursor high watermark for one room or friendship. */
public record V1ConversationWatermarkRow(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long lastSequence) {}
