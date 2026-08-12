package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

/** Authoritative active-room membership query with one overflow sentinel row. */
@FunctionalInterface
public interface LegacyV1RoomMemberListPort {
    QueryResult list(UUID actorAccountId, long legacyRoomId, int limitPlusOne);

    sealed interface QueryResult {
        record Authorized(List<LegacyV1RoomMemberEntry> members) implements QueryResult {
            public Authorized { members = List.copyOf(members); }
        }
        enum Rejected implements QueryResult { ROOM_ACCESS_DENIED }
    }
}
