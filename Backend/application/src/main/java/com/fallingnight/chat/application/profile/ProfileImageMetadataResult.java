package com.fallingnight.chat.application.profile;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public sealed interface ProfileImageMetadataResult {
    record Committed(String objectKey, long version, boolean changed, Instant updatedAt,
            Optional<String> cleanupObjectKey, Set<UUID> roomPeerAccountIds)
            implements ProfileImageMetadataResult {
        public Committed {
            Objects.requireNonNull(objectKey, "objectKey");
            Objects.requireNonNull(updatedAt, "updatedAt");
            cleanupObjectKey = Objects.requireNonNull(cleanupObjectKey, "cleanupObjectKey");
            roomPeerAccountIds = Set.copyOf(Objects.requireNonNull(
                    roomPeerAccountIds, "roomPeerAccountIds"));
            if (!objectKey.startsWith("avatars/sha256/") || version < 1
                    || (!changed && (cleanupObjectKey.isPresent()
                        || !roomPeerAccountIds.isEmpty()))
                    || cleanupObjectKey.filter(objectKey::equals).isPresent()
                    || roomPeerAccountIds.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("invalid profile image commit result");
        }
    }

    enum Rejected implements ProfileImageMetadataResult {
        ACCOUNT_UNAVAILABLE,
        ROOM_ADMIN_REQUIRED,
        OBJECT_EVIDENCE_CONFLICT
    }
}
