package com.fallingnight.chat.application.attachment;

import java.util.Arrays;
import java.util.Objects;

/** Exact immutable object constraints derived from durable attachment metadata. */
public record AttachmentUploadTarget(
        String objectKey,
        String mediaType,
        long byteSize,
        byte[] contentSha256) {
    public AttachmentUploadTarget {
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(contentSha256, "contentSha256");
        if (objectKey.isBlank() || objectKey.length() > 1024
                || byteSize < 1 || byteSize > AttachmentRegistration.MAX_BYTE_SIZE
                || contentSha256.length != 32) {
            throw new IllegalArgumentException("upload target is invalid");
        }
        contentSha256 = Arrays.copyOf(contentSha256, contentSha256.length);
    }

    public static AttachmentUploadTarget from(RegisteredAttachment attachment) {
        Objects.requireNonNull(attachment, "attachment");
        return new AttachmentUploadTarget(
                attachment.objectKey().orElseThrow(), attachment.mediaType().orElseThrow(),
                attachment.byteSize(), attachment.contentSha256().orElseThrow());
    }

    @Override
    public byte[] contentSha256() {
        return Arrays.copyOf(contentSha256, contentSha256.length);
    }
}
