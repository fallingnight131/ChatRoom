package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Durable mapped room-member projection before process-local presence is joined. */
public record LegacyV1RoomMemberEntry(UUID accountId, String username,
        String displayName, Role role) {
    public enum Role { OWNER, ADMIN, MEMBER }
    public LegacyV1RoomMemberEntry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        if (username.isBlank() || displayName.isBlank()
                || username.codePointCount(0, username.length()) > 128
                || displayName.codePointCount(0, displayName.length()) > 100
                || username.codePoints().anyMatch(Character::isISOControl)
                || displayName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("V1 room member identity");
        }
    }
}
