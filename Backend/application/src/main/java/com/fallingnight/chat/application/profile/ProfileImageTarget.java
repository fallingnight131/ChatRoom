package com.fallingnight.chat.application.profile;

import java.util.Objects;
import java.util.UUID;

public sealed interface ProfileImageTarget {
    UUID actorAccountId();

    record Account(UUID actorAccountId) implements ProfileImageTarget {
        public Account { Objects.requireNonNull(actorAccountId, "actorAccountId"); }
    }

    record LegacyRoom(UUID actorAccountId, long legacyRoomId) implements ProfileImageTarget {
        public LegacyRoom {
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid legacy room ID");
        }
    }
}
