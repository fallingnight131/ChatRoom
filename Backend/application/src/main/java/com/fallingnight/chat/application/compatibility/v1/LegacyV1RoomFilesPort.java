package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** PostgreSQL authorization/projection boundary for room administrators. */
@FunctionalInterface
public interface LegacyV1RoomFilesPort {
    QueryResult read(UUID actorAccountId, long legacyRoomId);

    sealed interface QueryResult {
        record Authorized(LegacyV1RoomFiles files) implements QueryResult {
            public Authorized {
                if (files == null) throw new NullPointerException("files");
            }
        }
        enum Rejected implements QueryResult { ROOM_ADMIN_REQUIRED }
    }
}
