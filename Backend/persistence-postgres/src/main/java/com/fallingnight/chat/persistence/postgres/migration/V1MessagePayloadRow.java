package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;

/** V1 retained message body/attachment metadata read for content mapping. */
public record V1MessagePayloadRow(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        String contentType,
        String content,
        String fileName,
        long fileSize,
        long fileId,
        boolean fileCleared,
        String clearReason,
        String thumbnail,
        boolean recalled) {}
