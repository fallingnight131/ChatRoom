package com.fallingnight.chat.application.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Identity-free durable Web Push backlog snapshot at one observation instant. */
public record WebPushOutboxStatus(
        long pending,
        long ready,
        long leased,
        long delayed,
        long expired,
        long retried,
        int maximumAttemptCount,
        Optional<Instant> oldestCommittedAt) {
    public WebPushOutboxStatus {
        oldestCommittedAt = Objects.requireNonNull(oldestCommittedAt, "oldestCommittedAt");
        long partitioned;
        try {
            partitioned = Math.addExact(Math.addExact(ready, leased),
                    Math.addExact(delayed, expired));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Web Push outbox status overflows", exception);
        }
        if (pending < 0 || ready < 0 || leased < 0 || delayed < 0 || expired < 0
                || retried < 0 || maximumAttemptCount < 0
                || partitioned != pending
                || retried > pending
                || (retried == 0 && maximumAttemptCount > 1)
                || (retried > 0 && maximumAttemptCount < 2)
                || (pending == 0) != oldestCommittedAt.isEmpty()
                || (pending == 0 && maximumAttemptCount != 0)) {
            throw new IllegalArgumentException("invalid Web Push outbox status");
        }
    }

    public long oldestAgeSeconds(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt");
        return oldestCommittedAt.map(committed -> Math.max(0L,
                Duration.between(committed, observedAt).toSeconds())).orElse(0L);
    }
}
