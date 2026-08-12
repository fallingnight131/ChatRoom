package com.fallingnight.chat.application.attachment;

import java.util.Objects;
import java.util.UUID;

/** Durable revoked attachment whose server-generated object still needs deletion. */
public record AttachmentCleanupCandidate(UUID attachmentId, String objectKey) {
    public AttachmentCleanupCandidate {
        Objects.requireNonNull(attachmentId, "attachmentId");
        Objects.requireNonNull(objectKey, "objectKey");
        if (!objectKey.startsWith("attachments/")
                || objectKey.length() == "attachments/".length()
                || objectKey.length() > 1024
                || objectKey.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("objectKey is invalid");
        }
    }
}
