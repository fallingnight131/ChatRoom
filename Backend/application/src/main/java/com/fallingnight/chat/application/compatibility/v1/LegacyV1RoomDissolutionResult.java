package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public sealed interface LegacyV1RoomDissolutionResult {
    record Dissolved(UUID conversationId, long legacyRoomId, String roomName,
            Set<UUID> affectedAccountIds, boolean changed, Instant dissolvedAt)
            implements LegacyV1RoomDissolutionResult {
        public Dissolved {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(roomName, "roomName");
            affectedAccountIds = Set.copyOf(Objects.requireNonNull(
                    affectedAccountIds, "affectedAccountIds"));
            Objects.requireNonNull(dissolvedAt, "dissolvedAt");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || roomName.isBlank() || roomName.codePointCount(0, roomName.length()) > 100
                    || affectedAccountIds.stream().anyMatch(Objects::isNull)
                    || (changed && affectedAccountIds.isEmpty())) {
                throw new IllegalArgumentException("invalid V1 room dissolution result");
            }
        }
    }

    enum Rejected implements LegacyV1RoomDissolutionResult {
        INVALID_INPUT,
        ROOM_ADMIN_REQUIRED,
        NOT_FOUND
    }
}
