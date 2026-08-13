package com.fallingnight.chat.application.profile;

import java.util.Objects;

public sealed interface ProfileImageMutationResult {
    record Committed(ProfileImageMetadataResult.Committed metadata)
            implements ProfileImageMutationResult {
        public Committed { Objects.requireNonNull(metadata, "metadata"); }
    }

    enum Rejected implements ProfileImageMutationResult {
        INVALID_IMAGE,
        ACCOUNT_UNAVAILABLE,
        ROOM_ADMIN_REQUIRED,
        OBJECT_EVIDENCE_CONFLICT
    }
}
