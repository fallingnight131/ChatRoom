package com.fallingnight.chat.application.compatibility.v1;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded V1 latest-page or mixed forward-sequence room-history policy. */
public final class LegacyV1RoomHistoryService implements LegacyV1RoomHistoryUseCase {
    private final LegacyV1RoomHistoryPort history;

    public LegacyV1RoomHistoryService(LegacyV1RoomHistoryPort history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override public LegacyV1RoomHistoryResult read(LegacyV1RoomHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(query.accountId(), "accountId");
        if (query.legacyRoomId() <= 0 || query.legacyRoomId() > Integer.MAX_VALUE
                || query.limit() < 1 || query.limit() > 100 || query.beforeEpochMillis() < 0
                || query.afterSequence() != null && query.afterSequence() < 0
                || query.afterSequence() != null && query.beforeEpochMillis() != 0) {
            return query.afterSequence() != null && query.afterSequence() < 0
                    ? LegacyV1RoomHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR
                    : LegacyV1RoomHistoryResult.Rejected.INVALID_REQUEST;
        }
        LegacyV1RoomHistoryResult result = Objects.requireNonNull(
                history.read(query), "room history result");
        if (result instanceof LegacyV1RoomHistoryResult.Page page) validate(query, page);
        return result;
    }

    private static void validate(
            LegacyV1RoomHistoryQuery query, LegacyV1RoomHistoryResult.Page page) {
        if (page.legacyRoomId() != query.legacyRoomId()
                || page.sequenceMode() != (query.afterSequence() != null)
                || page.messages().size() + page.events().size() > query.limit()) {
            throw new IllegalStateException("inconsistent room history projection");
        }
        if (query.afterSequence() == null) return;
        List<Long> sequences = new ArrayList<>();
        page.messages().forEach(message -> sequences.add(message.syncSequence()));
        page.events().forEach(event -> sequences.add(event.sequence()));
        sequences.sort(Comparator.naturalOrder());
        if (sequences.stream().anyMatch(sequence -> sequence <= query.afterSequence())) {
            throw new IllegalStateException("room history did not advance cursor");
        }
    }
}
