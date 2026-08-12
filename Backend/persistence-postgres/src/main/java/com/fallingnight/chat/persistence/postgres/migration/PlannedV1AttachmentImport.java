package com.fallingnight.chat.persistence.postgres.migration;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/** Canonical attachment import row; unavailable history contains no object evidence. */
public record PlannedV1AttachmentImport(
        PlannedV1AttachmentSource source,
        Optional<String> objectKey,
        Optional<String> mediaType,
        Optional<byte[]> contentSha256,
        Optional<Instant> readyAt,
        Optional<Instant> unavailableAt,
        Optional<String> unavailableReason) {
    public PlannedV1AttachmentImport {
        objectKey = objectKey == null ? Optional.empty() : objectKey;
        mediaType = mediaType == null ? Optional.empty() : mediaType;
        contentSha256 = contentSha256 == null ? Optional.empty()
                : contentSha256.map(value -> Arrays.copyOf(value, value.length));
        readyAt = readyAt == null ? Optional.empty() : readyAt;
        unavailableAt = unavailableAt == null ? Optional.empty() : unavailableAt;
        unavailableReason = unavailableReason == null ? Optional.empty() : unavailableReason;
    }

    public boolean unavailable() {
        return unavailableAt.isPresent();
    }

    @Override
    public Optional<byte[]> contentSha256() {
        return contentSha256.map(value -> Arrays.copyOf(value, value.length));
    }
}
