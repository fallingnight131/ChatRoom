package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomKickResult {
    record Kicked(UUID conversationId, long legacyRoomId, String roomName,
            UUID targetAccountId, String targetUsername, String targetDisplayName,
            boolean changed, Instant kickedAt) implements LegacyV1RoomKickResult {
        public Kicked {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(targetAccountId, "targetAccountId");
            Objects.requireNonNull(kickedAt, "kickedAt");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || !valid(roomName, 100) || !valid(targetUsername, 128)
                    || !valid(targetDisplayName, 100)) {
                throw new IllegalArgumentException("invalid V1 room kick result");
            }
        }

        private static boolean valid(String value, int maximumCodePoints) {
            return value != null && !value.isBlank() && value.equals(value.strip())
                    && value.codePointCount(0, value.length()) <= maximumCodePoints
                    && value.codePoints().noneMatch(Character::isISOControl);
        }
    }

    enum Rejected implements LegacyV1RoomKickResult {
        INVALID_INPUT,
        ROOM_ADMIN_REQUIRED,
        TARGET_NOT_ACTIVE_MEMBER,
        TARGET_ROLE_PROTECTED
    }
}
