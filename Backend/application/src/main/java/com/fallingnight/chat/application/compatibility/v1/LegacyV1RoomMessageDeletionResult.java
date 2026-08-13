package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public sealed interface LegacyV1RoomMessageDeletionResult {
    record Deleted(boolean duplicate, UUID conversationId, long legacyRoomId,
            String clientOperationId, LegacyV1RoomMessageDeletionMode mode,
            List<Long> legacyMessageIds, List<Long> legacyFileIds, long cutoffEpochMillis,
            int deletedCount, long sequence, Instant occurredAt)
            implements LegacyV1RoomMessageDeletionResult {
        public Deleted {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(clientOperationId, "clientOperationId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(occurredAt, "occurredAt");
            legacyMessageIds = positiveDistinct(legacyMessageIds, "message ids");
            legacyFileIds = positiveDistinct(legacyFileIds, "file ids");
            if (legacyRoomId <= 0 || legacyRoomId > Integer.MAX_VALUE
                    || clientOperationId.isBlank() || cutoffEpochMillis < 0
                    || deletedCount < 0 || sequence <= 0
                    || legacyMessageIds.size() > LegacyV1RoomMessageDeletionService.MAX_SELECTED
                    || legacyFileIds.size() > LegacyV1RoomMessageDeletionService.MAX_FILES
                    || (mode == LegacyV1RoomMessageDeletionMode.SELECTED
                        && deletedCount < legacyMessageIds.size())
                    || (mode != LegacyV1RoomMessageDeletionMode.SELECTED
                        && !legacyMessageIds.isEmpty())
                    || mode.usesCutoff() != (cutoffEpochMillis > 0)) {
                throw new IllegalArgumentException("invalid V1 room message deletion result");
            }
        }

        private static List<Long> positiveDistinct(List<Long> values, String label) {
            values = List.copyOf(Objects.requireNonNull(values, label));
            HashSet<Long> unique = new HashSet<>();
            if (values.stream().anyMatch(value -> value == null || value <= 0
                    || value > Integer.MAX_VALUE || !unique.add(value))) {
                throw new IllegalArgumentException("invalid " + label);
            }
            return values;
        }
    }

    enum Rejected implements LegacyV1RoomMessageDeletionResult {
        ROOM_ADMIN_REQUIRED,
        INVALID_INPUT,
        CLIENT_OPERATION_ID_CONFLICT,
        DELETE_SCOPE_TOO_LARGE
    }
}
