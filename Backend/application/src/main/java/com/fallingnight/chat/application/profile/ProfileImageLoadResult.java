package com.fallingnight.chat.application.profile;

import java.time.Instant;
import java.util.Objects;

public sealed interface ProfileImageLoadResult {
    record Loaded(ProfileImageObjectPayload payload, int width, int height,
            long version, Instant updatedAt) implements ProfileImageLoadResult, AutoCloseable {
        public Loaded {
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (width < 1 || width > CanonicalProfileImage.MAX_DIMENSION
                    || height < 1 || height > CanonicalProfileImage.MAX_DIMENSION
                    || version < 1)
                throw new IllegalArgumentException("invalid loaded profile image");
        }
        @Override public void close() { payload.close(); }
    }
    enum Missing implements ProfileImageLoadResult { INSTANCE }
    enum Rejected implements ProfileImageLoadResult { ACCESS_DENIED }
}
