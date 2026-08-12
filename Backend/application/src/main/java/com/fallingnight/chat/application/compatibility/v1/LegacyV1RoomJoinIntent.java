package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.StoredCredential;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Validated join intent carrying the exact access snapshot used for authorization. */
public record LegacyV1RoomJoinIntent(UUID actorAccountId, UUID conversationId,
        long legacyRoomId, Optional<StoredCredential> expectedJoinCredential) {
    public LegacyV1RoomJoinIntent {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(conversationId, "conversationId");
        expectedJoinCredential = Objects.requireNonNull(
                expectedJoinCredential, "expectedJoinCredential");
        if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                || expectedJoinCredential.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V1 room join intent");
        }
    }
}
