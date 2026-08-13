package com.fallingnight.chat.application.profile;

import java.util.Objects;
import java.util.UUID;

public sealed interface ProfileImageReadTarget {
    UUID actorAccountId();

    record AccountByUsername(UUID actorAccountId, String username)
            implements ProfileImageReadTarget {
        public AccountByUsername {
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            if (username == null || username.isBlank() || !username.equals(username.strip())
                    || username.codePointCount(0, username.length()) > 128
                    || username.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid profile username");
        }
    }

    record LegacyRoom(UUID actorAccountId, long legacyRoomId)
            implements ProfileImageReadTarget {
        public LegacyRoom {
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid legacy room ID");
        }
    }
}
