package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.time.Instant;
import java.util.Arrays;

/** Independently verified target-object facts for one active V1 file. */
public record V1AttachmentObjectEvidence(
        LegacyV1ConversationKind legacyKind,
        long legacyFileId,
        String objectKey,
        String mediaType,
        long byteSize,
        byte[] contentSha256,
        Instant sealedAt) {
    public V1AttachmentObjectEvidence {
        contentSha256 = contentSha256 == null
                ? null : Arrays.copyOf(contentSha256, contentSha256.length);
    }

    @Override
    public byte[] contentSha256() {
        return contentSha256 == null
                ? null : Arrays.copyOf(contentSha256, contentSha256.length);
    }
}
