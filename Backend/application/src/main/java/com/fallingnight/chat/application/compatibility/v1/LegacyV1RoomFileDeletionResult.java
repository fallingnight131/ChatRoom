package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomFileDeletionResult {
    record Deleted(boolean duplicate, UUID conversationId, long legacyRoomId,
            String clientOperationId, List<Long> legacyMessageIds,
            List<Long> legacyFileIds, long sequence, Instant occurredAt,
            long usedFileSpace, long maxFileSpace) implements LegacyV1RoomFileDeletionResult {
        public Deleted {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(clientOperationId, "clientOperationId");
            Objects.requireNonNull(legacyMessageIds, "legacyMessageIds");
            Objects.requireNonNull(legacyFileIds, "legacyFileIds");
            Objects.requireNonNull(occurredAt, "occurredAt");
            legacyMessageIds = List.copyOf(legacyMessageIds);
            legacyFileIds = List.copyOf(legacyFileIds);
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || clientOperationId.isBlank()
                    || legacyMessageIds.size() != legacyFileIds.size()
                    || legacyFileIds.size() > LegacyV1RoomFileDeletionService.MAX_FILES
                    || sequence <= 0 || usedFileSpace < 0 || maxFileSpace < 0
                    || usedFileSpace > maxFileSpace
                    || !positiveDistinct(legacyMessageIds)
                    || !positiveDistinct(legacyFileIds)) {
                throw new IllegalArgumentException("invalid V1 room file deletion result");
            }
        }

        public int deletedCount() { return legacyFileIds.size(); }

        private static boolean positiveDistinct(List<Long> values) {
            return values.stream().allMatch(value -> value != null && value > 0
                    && value <= Integer.MAX_VALUE)
                    && values.stream().distinct().count() == values.size();
        }
    }

    enum Rejected implements LegacyV1RoomFileDeletionResult {
        ROOM_ADMIN_REQUIRED,
        INVALID_INPUT,
        CLIENT_OPERATION_ID_CONFLICT
    }
}
