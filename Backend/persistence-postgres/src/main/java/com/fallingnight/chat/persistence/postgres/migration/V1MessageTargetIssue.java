package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;

/** Safe PostgreSQL preview conflict without message content or user-visible metadata. */
public record V1MessageTargetIssue(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        String code,
        String message) {}
