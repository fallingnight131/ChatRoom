package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

public record LegacyV1UserSearchUser(
        long userId,
        String username,
        String displayName,
        boolean online) {
    public LegacyV1UserSearchUser {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        if (userId <= 0 || userId > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("userId outside V1 range");
        }
    }
}
