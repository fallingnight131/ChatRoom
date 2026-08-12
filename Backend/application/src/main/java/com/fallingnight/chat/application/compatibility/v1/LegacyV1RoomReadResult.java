package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomReadResult {
    record Marked(UUID conversationId, long legacyRoomId, long previousSequence,
            long lastReadSequence, boolean changed) implements LegacyV1RoomReadResult {
        public Marked {
            Objects.requireNonNull(conversationId, "conversationId");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || previousSequence < 0 || lastReadSequence < previousSequence
                    || changed != (lastReadSequence > previousSequence)) {
                throw new IllegalArgumentException("room read cursor result");
            }
        }
    }

    enum Rejected implements LegacyV1RoomReadResult {
        ROOM_ACCESS_DENIED,
        INVALID_ROOM_ID
    }
}
