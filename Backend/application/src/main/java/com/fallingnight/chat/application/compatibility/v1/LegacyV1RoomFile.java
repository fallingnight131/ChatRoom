package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/** Safe active-file projection for the V1 administrator room file manager. */
public record LegacyV1RoomFile(
        long legacyFileId,
        String fileName,
        long byteSize,
        Instant createdAt) {
    public static final long MAX_BYTE_SIZE = 10_737_418_240L;
    public LegacyV1RoomFile {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(createdAt, "createdAt");
        if (legacyFileId <= 0 || legacyFileId > Integer.MAX_VALUE
                || fileName.isBlank()
                || fileName.getBytes(StandardCharsets.UTF_8).length > 255
                || byteSize < 1 || byteSize > MAX_BYTE_SIZE) {
            throw new IllegalArgumentException("invalid V1 room file projection");
        }
    }
}
