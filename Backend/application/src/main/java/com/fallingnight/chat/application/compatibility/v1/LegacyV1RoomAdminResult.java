package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomAdminResult {
    record Changed(UUID conversationId, long legacyRoomId, UUID targetAccountId,
            String targetUsername, String targetDisplayName, boolean admin,
            boolean changed) implements LegacyV1RoomAdminResult {
        public Changed {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(targetAccountId, "targetAccountId");
            Objects.requireNonNull(targetUsername, "targetUsername");
            Objects.requireNonNull(targetDisplayName, "targetDisplayName");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || !validText(targetUsername, 128)
                    || !validText(targetDisplayName, 100)) {
                throw new IllegalArgumentException("invalid V1 room administrator result");
            }
        }

        private static boolean validText(String value, int maximumCodePoints) {
            return !value.isBlank() && value.equals(value.strip())
                    && value.codePointCount(0, value.length()) <= maximumCodePoints
                    && value.codePoints().noneMatch(Character::isISOControl);
        }
    }

    enum Rejected implements LegacyV1RoomAdminResult {
        INVALID_INPUT,
        ROOM_ADMIN_REQUIRED,
        SELF_DEMOTION_REQUIRED,
        TARGET_NOT_ACTIVE_MEMBER,
        OWNER_PROTECTED
    }
}
