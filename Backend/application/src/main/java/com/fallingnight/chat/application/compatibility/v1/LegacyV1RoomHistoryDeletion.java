package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One UUID-free V1 replayable room administrative-deletion event. */
public record LegacyV1RoomHistoryDeletion(
        long legacyEventId,
        long sequence,
        String operatorName,
        String clientOperationId,
        String mode,
        List<Long> legacyMessageIds,
        List<Long> deletedFileIds,
        long cutoffEpochMillis,
        int deletedCount,
        Instant occurredAt) {
    public static final int MAX_IDS_PER_EVENT = 1_000;

    public LegacyV1RoomHistoryDeletion {
        if (legacyEventId <= 0 || legacyEventId > Integer.MAX_VALUE || sequence <= 0
                || cutoffEpochMillis < 0 || deletedCount < 0) {
            throw new IllegalArgumentException("room deletion identity");
        }
        Objects.requireNonNull(operatorName, "operatorName");
        Objects.requireNonNull(clientOperationId, "clientOperationId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(occurredAt, "occurredAt");
        legacyMessageIds = positiveDistinct(legacyMessageIds, "message ids");
        deletedFileIds = positiveDistinct(deletedFileIds, "file ids");
        if (legacyMessageIds.size() > MAX_IDS_PER_EVENT
                || deletedFileIds.size() > MAX_IDS_PER_EVENT) {
            throw new IllegalArgumentException("room deletion event is too large");
        }
        if (!mode.equals("selected") && !mode.equals("all")
                && !mode.equals("before") && !mode.equals("after")) {
            throw new IllegalArgumentException("room deletion mode");
        }
    }

    private static List<Long> positiveDistinct(List<Long> values, String label) {
        values = List.copyOf(Objects.requireNonNull(values, label));
        HashSet<Long> unique = new HashSet<>();
        if (values.stream().anyMatch(value -> value <= 0 || value > Integer.MAX_VALUE
                || !unique.add(value))) {
            throw new IllegalArgumentException("invalid room deletion " + label);
        }
        return values;
    }
}
