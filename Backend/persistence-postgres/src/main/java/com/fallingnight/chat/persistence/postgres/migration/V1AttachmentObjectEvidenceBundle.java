package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;

/** Evidence manifest bound to one exact V1 attachment source fingerprint. */
public record V1AttachmentObjectEvidenceBundle(
        String sourceFingerprintSha256,
        List<V1AttachmentObjectEvidence> objects) {
    public V1AttachmentObjectEvidenceBundle {
        Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        objects = List.copyOf(objects);
    }
}
