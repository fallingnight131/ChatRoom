package com.fallingnight.chat.application.messaging;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One current-state page ordered by descending conversation sequence. */
public record MessageSearchPage(
        UUID conversationId,
        List<StoredMessage> hits,
        long nextBeforeSequence,
        boolean hasMore) {
    public MessageSearchPage {
        Objects.requireNonNull(conversationId, "conversationId");
        hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
        if (hits.size() > MessageSearchQuery.MAX_LIMIT || (hasMore && hits.isEmpty())) {
            throw new IllegalArgumentException("search page bounds are invalid");
        }
        Long previous = null;
        for (StoredMessage hit : hits) {
            if (!conversationId.equals(hit.conversationId())
                    || (previous != null && hit.conversationSequence() >= previous)) {
                throw new IllegalArgumentException(
                        "search hits must share a conversation and descend by sequence");
            }
            previous = hit.conversationSequence();
        }
        long expectedCursor = previous == null ? 0 : previous;
        if (nextBeforeSequence != expectedCursor) {
            throw new IllegalArgumentException("next cursor must identify the last hit");
        }
    }
}
