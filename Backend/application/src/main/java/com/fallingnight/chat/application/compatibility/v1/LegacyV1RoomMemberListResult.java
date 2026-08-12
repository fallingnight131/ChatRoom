package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.Objects;

public sealed interface LegacyV1RoomMemberListResult {
    record Listed(long legacyRoomId, List<LegacyV1RoomMemberUser> users)
            implements LegacyV1RoomMemberListResult {
        public Listed {
            users = List.copyOf(Objects.requireNonNull(users, "users"));
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("V1 room member list room ID");
            }
        }
    }
    enum Rejected implements LegacyV1RoomMemberListResult {
        INVALID_INPUT, ROOM_ACCESS_DENIED, ROOM_TOO_LARGE
    }
}
