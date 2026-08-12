package com.fallingnight.chat.application.compatibility.v1;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
public sealed interface LegacyV1RoomMessageResult {
    record Accepted(boolean duplicate, long legacyRoomId, long legacyMessageId,
            long sequence, Instant acceptedAt, UUID conversationId)
            implements LegacyV1RoomMessageResult {
        public Accepted {
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || legacyMessageId <= 0 || legacyMessageId > Integer.MAX_VALUE
                    || sequence <= 0) throw new IllegalArgumentException("room message identity");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            Objects.requireNonNull(conversationId, "conversationId");
        }
    }
    enum Rejected implements LegacyV1RoomMessageResult {
        ROOM_ACCESS_DENIED, INVALID_MESSAGE, INVALID_CLIENT_MESSAGE_ID,
        CLIENT_MESSAGE_ID_CONFLICT
    }
}
