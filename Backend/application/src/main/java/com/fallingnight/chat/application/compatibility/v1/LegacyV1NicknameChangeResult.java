package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public sealed interface LegacyV1NicknameChangeResult {
    record RoomAudience(long legacyRoomId, Set<UUID> accountIds) {
        public RoomAudience {
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE)
                throw new IllegalArgumentException("invalid legacy room ID");
            accountIds = Set.copyOf(Objects.requireNonNull(accountIds, "accountIds"));
            if (accountIds.isEmpty() || accountIds.stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("invalid room audience");
        }
    }

    record Changed(UUID accountId, String oldDisplayName, String newDisplayName,
            boolean changed, Instant changedAt, List<RoomAudience> roomAudiences)
            implements LegacyV1NicknameChangeResult {
        public Changed {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(changedAt, "changedAt");
            roomAudiences = List.copyOf(Objects.requireNonNull(roomAudiences, "roomAudiences"));
            if (!valid(oldDisplayName) || !valid(newDisplayName)
                    || changed == oldDisplayName.equals(newDisplayName)
                    || (!changed && !roomAudiences.isEmpty())
                    || roomAudiences.stream().map(RoomAudience::legacyRoomId).distinct().count()
                        != roomAudiences.size())
                throw new IllegalArgumentException("invalid nickname change result");
        }

        private static boolean valid(String value) {
            return value != null && !value.isBlank() && value.equals(value.strip())
                    && value.codePointCount(0, value.length())
                        <= LegacyV1NicknameChangeService.MAX_DISPLAY_NAME_CODE_POINTS
                    && value.codePoints().noneMatch(Character::isISOControl);
        }
    }

    enum Rejected implements LegacyV1NicknameChangeResult {
        INVALID_INPUT,
        ACCOUNT_UNAVAILABLE
    }
}
