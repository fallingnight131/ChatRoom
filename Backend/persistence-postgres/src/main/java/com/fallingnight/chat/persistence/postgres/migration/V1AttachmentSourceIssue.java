package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;

/** Fixed-code source problem; intentionally excludes names, paths, URLs, and content. */
public record V1AttachmentSourceIssue(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyFileId,
        long legacyMessageId,
        String code,
        String message) { }
