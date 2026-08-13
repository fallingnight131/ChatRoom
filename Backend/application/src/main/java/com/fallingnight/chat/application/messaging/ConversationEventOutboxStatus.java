package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Identity-free operational snapshot of unpublished conversation events. */
public record ConversationEventOutboxStatus(
        long unpublished,
        long ready,
        long leased,
        long delayed,
        long retried,
        int maximumAttemptCount,
        Optional<Instant> oldestCreatedAt) {
    public ConversationEventOutboxStatus {
        oldestCreatedAt = Objects.requireNonNull(oldestCreatedAt, "oldestCreatedAt");
        if (unpublished < 0 || ready < 0 || leased < 0 || delayed < 0 || retried < 0
                || maximumAttemptCount < 0 || ready > unpublished || leased > unpublished
                || delayed > unpublished || retried > unpublished
                || (unpublished == 0) != oldestCreatedAt.isEmpty()
                || (unpublished == 0 && maximumAttemptCount != 0)) {
            throw new IllegalArgumentException("invalid conversation event outbox status");
        }
    }

    public long oldestAgeSeconds(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        return oldestCreatedAt.map(created -> Math.max(0L,
                java.time.Duration.between(created, observedAt).toSeconds())).orElse(0L);
    }
}
