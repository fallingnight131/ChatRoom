package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** Monotonic server-authorized V1 room read-cursor boundary. */
public final class LegacyV1RoomReadService implements LegacyV1RoomReadUseCase {
    private final LegacyV1RoomReadPort reads;

    public LegacyV1RoomReadService(LegacyV1RoomReadPort reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    @Override public LegacyV1RoomReadResult markRead(LegacyV1RoomReadCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        if (command.legacyRoomId() <= 0 || command.legacyRoomId() > Integer.MAX_VALUE) {
            return LegacyV1RoomReadResult.Rejected.INVALID_ROOM_ID;
        }
        LegacyV1RoomReadResult result = Objects.requireNonNull(
                reads.markRead(command), "room read result");
        if (result instanceof LegacyV1RoomReadResult.Marked marked
                && marked.legacyRoomId() != command.legacyRoomId()) {
            throw new IllegalStateException("room read identity changed");
        }
        return result;
    }
}
