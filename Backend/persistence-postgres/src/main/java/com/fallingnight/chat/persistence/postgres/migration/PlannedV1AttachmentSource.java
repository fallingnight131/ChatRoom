package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Validated V1 file/message identity awaiting independently verified object evidence. */
public record PlannedV1AttachmentSource(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyFileId,
        long legacyMessageId,
        long legacyUploaderUserId,
        UUID conversationId,
        UUID attachmentId,
        UUID messageId,
        UUID ownerAccountId,
        UUID ownerDeviceId,
        String clientAttachmentId,
        String fileName,
        long byteSize,
        String legacyContentType,
        boolean cleared,
        String clearReason,
        Instant fileCreatedAt,
        Instant messageAcceptedAt) {
    public PlannedV1AttachmentSource {
        Objects.requireNonNull(legacyKind, "legacyKind");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(ownerDeviceId, "ownerDeviceId");
        Objects.requireNonNull(clientAttachmentId, "clientAttachmentId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(legacyContentType, "legacyContentType");
        Objects.requireNonNull(clearReason, "clearReason");
        Objects.requireNonNull(fileCreatedAt, "fileCreatedAt");
        Objects.requireNonNull(messageAcceptedAt, "messageAcceptedAt");
    }
}
