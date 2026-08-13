package com.fallingnight.chat.application.profile;

import java.util.Objects;
import java.util.Optional;

public sealed interface ProfileImageMutationResult {
    record Committed(ProfileImageMetadataResult.Committed metadata,
            Optional<ProfileImageObjectPayload> notificationPayload)
            implements ProfileImageMutationResult, AutoCloseable {
        public Committed {
            Objects.requireNonNull(metadata, "metadata");
            notificationPayload = Objects.requireNonNull(
                    notificationPayload, "notificationPayload");
            if (metadata.changed() != notificationPayload.isPresent())
                throw new IllegalArgumentException(
                        "notification bytes must exist exactly for a changed image");
        }
        @Override public void close() {
            notificationPayload.ifPresent(ProfileImageObjectPayload::close);
        }
    }

    enum Rejected implements ProfileImageMutationResult {
        INVALID_IMAGE,
        ACCOUNT_UNAVAILABLE,
        ROOM_ADMIN_REQUIRED,
        OBJECT_EVIDENCE_CONFLICT
    }
}
