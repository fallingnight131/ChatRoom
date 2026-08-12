package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Server-authorized read-only V1 room file administration boundary. */
public final class LegacyV1RoomFilesService implements LegacyV1RoomFilesUseCase {
    private final LegacyV1RoomFilesPort files;

    public LegacyV1RoomFilesService(LegacyV1RoomFilesPort files) {
        this.files = Objects.requireNonNull(files, "files");
    }

    @Override
    public LegacyV1RoomFilesResult read(UUID actor, long roomId) {
        Objects.requireNonNull(actor, "actor");
        if (roomId <= 0 || roomId > Integer.MAX_VALUE) {
            return LegacyV1RoomFilesResult.Rejected.INVALID_INPUT;
        }
        LegacyV1RoomFilesPort.QueryResult result = Objects.requireNonNull(
                files.read(actor, roomId), "room files query result");
        if (result == LegacyV1RoomFilesPort.QueryResult.Rejected.ROOM_ADMIN_REQUIRED) {
            return LegacyV1RoomFilesResult.Rejected.ROOM_ADMIN_REQUIRED;
        }
        return new LegacyV1RoomFilesResult.Read(roomId,
                ((LegacyV1RoomFilesPort.QueryResult.Authorized) result).files());
    }
}
