package com.fallingnight.chat.application.messaging;

import java.util.List;
import java.util.Objects;

/** Bounded ordered page or opaque membership denial. */
public sealed interface MessageHistoryResult {
    record Page(
            List<StoredMessage> messages,
            long nextSequence,
            long latestSequence,
            boolean hasMore) implements MessageHistoryResult {
        public Page {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            if (nextSequence < 0 || latestSequence < 0) {
                throw new IllegalArgumentException("history cursor range is invalid");
            }
        }
    }

    enum Rejected implements MessageHistoryResult {
        NOT_AUTHORIZED
    }
}
