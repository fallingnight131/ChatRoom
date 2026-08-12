package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Validated persistence intent; contains only an encoded optional password. */
public record LegacyV1RoomCreationIntent(UUID actorAccountId, String clientRequestId,
        String roomName, Optional<LegacyV1RoomPasswordEncoding> encodedPassword) {
    public LegacyV1RoomCreationIntent {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(clientRequestId, "clientRequestId");
        Objects.requireNonNull(roomName, "roomName");
        encodedPassword = Objects.requireNonNull(encodedPassword, "encodedPassword");
        if (clientRequestId.isBlank() || roomName.isBlank()
                || encodedPassword.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("V1 room creation intent");
        }
    }
}
