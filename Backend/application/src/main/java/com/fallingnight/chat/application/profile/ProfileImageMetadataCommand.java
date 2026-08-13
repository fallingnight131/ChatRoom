package com.fallingnight.chat.application.profile;

import java.util.Objects;

public record ProfileImageMetadataCommand(ProfileImageTarget target,
        ProfileImageObjectEvidence object, int width, int height) {
    public ProfileImageMetadataCommand {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(object, "object");
        if (width < 1 || width > CanonicalProfileImage.MAX_DIMENSION
                || height < 1 || height > CanonicalProfileImage.MAX_DIMENSION)
            throw new IllegalArgumentException("invalid profile image dimensions");
    }
}
