package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomRenameResult {
    record Renamed(UUID conversationId, long legacyRoomId, String oldName,
            String newName, boolean changed, Instant updatedAt)
            implements LegacyV1RoomRenameResult {
        public Renamed {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || !valid(oldName) || !valid(newName)
                    || changed == oldName.equals(newName)) {
                throw new IllegalArgumentException("invalid V1 room rename result");
            }
        }

        private static boolean valid(String value) {
            return value != null && !value.isBlank() && value.equals(value.strip())
                    && value.codePointCount(0, value.length())
                        <= LegacyV1RoomRenameService.MAX_NAME_CODE_POINTS
                    && value.codePoints().noneMatch(Character::isISOControl);
        }
    }

    enum Rejected implements LegacyV1RoomRenameResult {
        INVALID_INPUT,
        ROOM_ADMIN_REQUIRED
    }
}
