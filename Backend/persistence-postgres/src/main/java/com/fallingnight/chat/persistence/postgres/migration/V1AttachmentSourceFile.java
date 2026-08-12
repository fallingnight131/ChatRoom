package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;

/** Raw V1 file-registry row; locator fields are fingerprint-only migration input. */
public record V1AttachmentSourceFile(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyFileId,
        long legacyUploaderUserId,
        String fileName,
        long byteSize,
        boolean cleared,
        String clearReason,
        Instant clearedAt,
        Instant createdAt,
        String sourcePath,
        String legacyObjectUrl) { }
