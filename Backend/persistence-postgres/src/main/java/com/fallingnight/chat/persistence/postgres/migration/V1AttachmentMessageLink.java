package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;

/** Raw V1 attachment-message link used to prove file/message graph consistency. */
public record V1AttachmentMessageLink(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        long legacySenderUserId,
        long legacyFileId,
        String contentType,
        String fileName,
        long byteSize,
        boolean fileCleared,
        String clearReason,
        Instant acceptedAt) { }
