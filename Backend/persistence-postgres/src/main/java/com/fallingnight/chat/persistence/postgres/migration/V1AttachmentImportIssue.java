package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;

/** Safe fixed-code evidence reconciliation issue. */
public record V1AttachmentImportIssue(
        LegacyV1ConversationKind legacyKind,
        long legacyFileId,
        String code,
        String message) { }
