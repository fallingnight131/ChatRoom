package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Validates a server-bound V1 leave request and verifies persistence identity. */
public final class LegacyV1RoomLeaveService implements LegacyV1RoomLeaveUseCase {
    private final LegacyV1RoomLeavePort rooms;

    public LegacyV1RoomLeaveService(LegacyV1RoomLeavePort rooms) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomLeaveResult leave(UUID actorAccountId, long legacyRoomId) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE) {
            return LegacyV1RoomLeaveResult.Rejected.INVALID_INPUT;
        }
        LegacyV1RoomLeaveResult result = Objects.requireNonNull(rooms.leave(
                new LegacyV1RoomLeaveIntent(actorAccountId, legacyRoomId)),
                "room leave result");
        if (result instanceof LegacyV1RoomLeaveResult.Left left
                && (!left.actorAccountId().equals(actorAccountId)
                        || left.legacyRoomId() != legacyRoomId)) {
            throw new IllegalStateException("V1 room leave identity changed");
        }
        return result;
    }
}
