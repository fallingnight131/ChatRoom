package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;

/** Safe blocking issue from V1 sequence/read-cursor planning. */
public record V1MessageStateImportIssue(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        String code,
        String message) {}
