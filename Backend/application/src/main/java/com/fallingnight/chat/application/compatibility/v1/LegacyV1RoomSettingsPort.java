package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Authoritative settings projection for an active room member. */
@FunctionalInterface
public interface LegacyV1RoomSettingsPort {
    QueryResult read(UUID actorAccountId, long legacyRoomId);

    sealed interface QueryResult {
        record Authorized(LegacyV1RoomSettings settings) implements QueryResult {
            public Authorized {
                if (settings == null) throw new NullPointerException("settings");
            }
        }
        enum Rejected implements QueryResult { ROOM_ACCESS_DENIED }
    }
}
