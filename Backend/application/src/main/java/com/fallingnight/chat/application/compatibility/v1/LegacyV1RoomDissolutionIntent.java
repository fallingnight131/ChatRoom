package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

public record LegacyV1RoomDissolutionIntent(UUID actorAccountId, long legacyRoomId) {
    public LegacyV1RoomDissolutionIntent {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
    }
}
