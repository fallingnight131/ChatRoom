package com.fallingnight.chat.application.attachment;

import java.util.Arrays;
import java.util.Objects;

/** Trusted object metadata returned after create-only upload sealing. */
public record StoredAttachmentObject(
        String objectKey,
        long byteSize,
        byte[] contentSha256) {
    public StoredAttachmentObject {
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(contentSha256, "contentSha256");
        if (objectKey.isBlank() || byteSize < 0 || contentSha256.length != 32) {
            throw new IllegalArgumentException("stored object metadata is invalid");
        }
        contentSha256 = Arrays.copyOf(contentSha256, contentSha256.length);
    }

    @Override
    public byte[] contentSha256() {
        return Arrays.copyOf(contentSha256, contentSha256.length);
    }
}
