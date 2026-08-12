package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Server-bound identity and legacy room target for one atomic leave decision. */
public record LegacyV1RoomLeaveIntent(UUID actorAccountId, long legacyRoomId) {
    public LegacyV1RoomLeaveIntent {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("V1 room leave intent");
        }
    }
}
