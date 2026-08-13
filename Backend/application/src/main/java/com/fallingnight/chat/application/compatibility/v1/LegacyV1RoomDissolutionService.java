package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Validates server-bound V1 room dissolution and persistence identity. */
public final class LegacyV1RoomDissolutionService implements LegacyV1RoomDissolutionUseCase {
    private final LegacyV1RoomDissolutionPort rooms;

    public LegacyV1RoomDissolutionService(LegacyV1RoomDissolutionPort rooms) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomDissolutionResult dissolve(
            UUID actorAccountId, long legacyRoomId) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE) {
            return LegacyV1RoomDissolutionResult.Rejected.INVALID_INPUT;
        }
        LegacyV1RoomDissolutionResult result = Objects.requireNonNull(rooms.dissolve(
                new LegacyV1RoomDissolutionIntent(actorAccountId, legacyRoomId)),
                "room dissolution result");
        if (result instanceof LegacyV1RoomDissolutionResult.Dissolved dissolved
                && (dissolved.legacyRoomId() != legacyRoomId
                    || (dissolved.changed()
                        && !dissolved.affectedAccountIds().contains(actorAccountId)))) {
            throw new IllegalStateException("V1 room dissolution identity changed");
        }
        return result;
    }
}
