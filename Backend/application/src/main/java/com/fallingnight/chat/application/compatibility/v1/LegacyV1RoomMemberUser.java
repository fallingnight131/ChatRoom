package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** UUID-free V1 room-member response projection. */
public record LegacyV1RoomMemberUser(String username, String displayName,
        boolean admin, boolean online) {
    public LegacyV1RoomMemberUser {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
    }
}
