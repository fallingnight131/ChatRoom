package com.fallingnight.chat.gateway.operations;

import java.time.Duration;
import java.util.Objects;

/** Bounded deterministic delay policy; deployment jitter remains a composition concern. */
public final class AttachmentCleanupBackoff {
    private final Duration healthyInterval;
    private final Duration initialFailureDelay;
    private final Duration maximumFailureDelay;

    public AttachmentCleanupBackoff(
            Duration healthyInterval,
            Duration initialFailureDelay,
            Duration maximumFailureDelay) {
        this.healthyInterval = bounded(
                healthyInterval, "healthyInterval", Duration.ofSeconds(10), Duration.ofDays(1));
        this.initialFailureDelay = bounded(
                initialFailureDelay, "initialFailureDelay", Duration.ofSeconds(1),
                Duration.ofHours(1));
        this.maximumFailureDelay = bounded(
                maximumFailureDelay, "maximumFailureDelay", initialFailureDelay,
                Duration.ofHours(6));
    }

    public Duration delay(int consecutiveFailures) {
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutiveFailures must not be negative");
        }
        if (consecutiveFailures == 0) {
            return healthyInterval;
        }
        Duration delay = initialFailureDelay;
        for (int attempt = 1;
                attempt < consecutiveFailures && delay.compareTo(maximumFailureDelay) < 0;
                attempt++) {
            if (delay.compareTo(maximumFailureDelay.dividedBy(2)) > 0) {
                return maximumFailureDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximumFailureDelay) > 0 ? maximumFailureDelay : delay;
    }

    private static Duration bounded(
            Duration value, String name, Duration minimum, Duration maximum) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return value;
    }
}
