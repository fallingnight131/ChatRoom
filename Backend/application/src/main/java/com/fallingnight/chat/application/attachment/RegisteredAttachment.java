package com.fallingnight.chat.application.attachment;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Safe metadata for one reserved attachment object; contains no storage grant. */
public record RegisteredAttachment(
        UUID attachmentId,
        UUID conversationId,
        UUID ownerAccountId,
        UUID ownerDeviceId,
        String clientAttachmentId,
        String objectKey,
        String fileName,
        String mediaType,
        long byteSize,
        byte[] contentSha256,
        AttachmentState state,
        Instant createdAt,
        Optional<Instant> readyAt,
        Optional<Instant> revokedAt) {
    public RegisteredAttachment {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(ownerDeviceId, "ownerDeviceId");
        Objects.requireNonNull(clientAttachmentId, "clientAttachmentId");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        readyAt = Objects.requireNonNull(readyAt, "readyAt");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
        if (byteSize < 1 || byteSize > AttachmentRegistration.MAX_BYTE_SIZE
                || contentSha256.length != 32) {
            throw new IllegalArgumentException("registered attachment bounds are invalid");
        }
        boolean timestampsMatch = switch (state) {
            case UPLOAD_PENDING -> readyAt.isEmpty() && revokedAt.isEmpty();
            case READY -> readyAt.isPresent() && revokedAt.isEmpty();
            case REVOKED -> revokedAt.isPresent();
        };
        if (!timestampsMatch) {
            throw new IllegalArgumentException("registered attachment lifecycle is invalid");
        }
        contentSha256 = Arrays.copyOf(contentSha256, contentSha256.length);
    }

    @Override
    public byte[] contentSha256() {
        return Arrays.copyOf(contentSha256, contentSha256.length);
    }
}
