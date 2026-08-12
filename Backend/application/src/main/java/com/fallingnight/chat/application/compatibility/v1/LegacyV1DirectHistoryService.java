package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bounded V1 latest-page or forward-sequence direct-history policy. */
public final class LegacyV1DirectHistoryService implements LegacyV1DirectHistoryUseCase {
    private static final int MAX_USERNAME_UTF8_BYTES = 128;
    private final LegacyV1DirectHistoryPort history;

    public LegacyV1DirectHistoryService(LegacyV1DirectHistoryPort history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override public LegacyV1DirectHistoryResult read(LegacyV1DirectHistoryQuery query) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(query.accountId(), "accountId");
        if (!validUsername(query.targetUsername()) || query.limit() < 1 || query.limit() > 100
                || query.beforeEpochMillis() < 0
                || query.afterSequence() != null && query.afterSequence() < 0
                || query.afterSequence() != null && query.beforeEpochMillis() != 0) {
            return query.afterSequence() != null && query.afterSequence() < 0
                    ? LegacyV1DirectHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR
                    : LegacyV1DirectHistoryResult.Rejected.INVALID_REQUEST;
        }
        LegacyV1DirectHistoryResult result = Objects.requireNonNull(
                history.read(query), "direct history result");
        if (result instanceof LegacyV1DirectHistoryResult.Page page
                && (!page.targetUsername().equals(query.targetUsername())
                    || page.sequenceMode() != (query.afterSequence() != null)
                    || page.messages().size() > query.limit())) {
            throw new IllegalStateException("inconsistent direct history projection");
        }
        return result;
    }

    private static boolean validUsername(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_USERNAME_UTF8_BYTES
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
