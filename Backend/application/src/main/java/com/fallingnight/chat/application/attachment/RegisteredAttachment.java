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
        Optional<String> objectKey,
        String fileName,
        Optional<String> mediaType,
        long byteSize,
        Optional<byte[]> contentSha256,
        AttachmentState state,
        Instant createdAt,
        Optional<Instant> readyAt,
        Optional<Instant> revokedAt,
        Optional<Instant> unavailableAt,
        Optional<String> unavailableReason) {
    public RegisteredAttachment {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(ownerDeviceId, "ownerDeviceId");
        Objects.requireNonNull(clientAttachmentId, "clientAttachmentId");
        objectKey = Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(fileName, "fileName");
        mediaType = Objects.requireNonNull(mediaType, "mediaType");
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        readyAt = Objects.requireNonNull(readyAt, "readyAt");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
        unavailableAt = Objects.requireNonNull(unavailableAt, "unavailableAt");
        unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
        if (byteSize < 1 || byteSize > AttachmentRegistration.MAX_BYTE_SIZE) {
            throw new IllegalArgumentException("registered attachment bounds are invalid");
        }
        boolean hasObjectEvidence = objectKey.isPresent() && mediaType.isPresent()
                && contentSha256.isPresent() && contentSha256.orElseThrow().length == 32;
        boolean hasNoObjectEvidence = objectKey.isEmpty() && mediaType.isEmpty()
                && contentSha256.isEmpty();
        boolean timestampsMatch = switch (state) {
            case UPLOAD_PENDING -> hasObjectEvidence && readyAt.isEmpty()
                    && revokedAt.isEmpty() && unavailableAt.isEmpty()
                    && unavailableReason.isEmpty();
            case READY -> hasObjectEvidence && readyAt.isPresent()
                    && revokedAt.isEmpty() && unavailableAt.isEmpty()
                    && unavailableReason.isEmpty();
            case REVOKED -> hasObjectEvidence && revokedAt.isPresent()
                    && unavailableAt.isEmpty() && unavailableReason.isEmpty();
            case UNAVAILABLE -> hasNoObjectEvidence && readyAt.isEmpty()
                    && revokedAt.isEmpty() && unavailableAt.isPresent()
                    && unavailableAt.orElseThrow().compareTo(createdAt) >= 0
                    && unavailableReason.filter(value -> !value.isBlank()
                            && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255)
                            .isPresent();
        };
        if (!timestampsMatch) {
            throw new IllegalArgumentException("registered attachment lifecycle is invalid");
        }
        contentSha256 = contentSha256.map(value -> Arrays.copyOf(value, value.length));
    }

    @Override
    public Optional<byte[]> contentSha256() {
        return contentSha256.map(value -> Arrays.copyOf(value, value.length));
    }
}
