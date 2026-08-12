package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;

/** Non-content-bearing blocker from V1 message payload planning. */
public record V1MessagePayloadImportIssue(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        String code,
        String message) {}
