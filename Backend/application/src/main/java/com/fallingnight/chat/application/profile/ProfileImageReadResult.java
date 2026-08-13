package com.fallingnight.chat.application.profile;

import java.time.Instant;
import java.util.Objects;

public sealed interface ProfileImageReadResult {
    record Found(ProfileImageObjectEvidence object, int width, int height,
            long version, Instant updatedAt) implements ProfileImageReadResult {
        public Found {
            Objects.requireNonNull(object, "object"); Objects.requireNonNull(updatedAt, "updatedAt");
            if (width < 1 || width > CanonicalProfileImage.MAX_DIMENSION
                    || height < 1 || height > CanonicalProfileImage.MAX_DIMENSION
                    || version < 1)
                throw new IllegalArgumentException("invalid profile image projection");
        }
    }
    enum Missing implements ProfileImageReadResult { INSTANCE }
    enum Rejected implements ProfileImageReadResult { ACCESS_DENIED }
}
