package com.fallingnight.chat.application.messaging;

import java.util.List;
import java.util.Objects;

/** Bounded mixed conversation-entry page or opaque membership denial. */
public sealed interface ConversationEntryHistoryResult {
    record Page(
            List<ConversationHistoryEntry> entries,
            long nextSequence,
            long latestSequence,
            boolean hasMore) implements ConversationEntryHistoryResult {
        public Page {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (nextSequence < 0 || latestSequence < 0) {
                throw new IllegalArgumentException("history cursor range is invalid");
            }
        }
    }

    enum Rejected implements ConversationEntryHistoryResult {
        NOT_AUTHORIZED
    }
}
