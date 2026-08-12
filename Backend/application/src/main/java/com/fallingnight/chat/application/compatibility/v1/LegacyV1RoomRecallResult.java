package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomRecallResult {
    record Recalled(boolean duplicate, UUID conversationId, long legacyRoomId,
            long legacyMessageId, long mutationSequence, Instant occurredAt)
            implements LegacyV1RoomRecallResult {
        public Recalled {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || legacyMessageId <= 0 || legacyMessageId > Integer.MAX_VALUE
                    || mutationSequence <= 0) {
                throw new IllegalArgumentException("room recall identity");
            }
        }
    }

    enum Rejected implements LegacyV1RoomRecallResult {
        ROOM_ACCESS_DENIED,
        RECALL_REJECTED,
        INVALID_REQUEST
    }
}
