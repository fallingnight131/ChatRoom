package com.fallingnight.chat.gateway.operations;

import java.time.Duration;
import java.util.Objects;

/** Bounded polling and dependency-failure delay policy for Web Push delivery. */
public final class WebPushDeliveryLoopBackoff {
    private final Duration drainInterval;
    private final Duration idleInterval;
    private final Duration initialFailureDelay;
    private final Duration maximumFailureDelay;

    public WebPushDeliveryLoopBackoff(
            Duration drainInterval,
            Duration idleInterval,
            Duration initialFailureDelay,
            Duration maximumFailureDelay) {
        this.drainInterval = bounded(
                drainInterval, Duration.ofMillis(10), Duration.ofSeconds(1),
                "drainInterval");
        this.idleInterval = bounded(
                idleInterval, Duration.ofMillis(100), Duration.ofMinutes(1),
                "idleInterval");
        this.initialFailureDelay = bounded(
                initialFailureDelay, Duration.ofMillis(100), Duration.ofMinutes(1),
                "initialFailureDelay");
        this.maximumFailureDelay = bounded(
                maximumFailureDelay, initialFailureDelay, Duration.ofMinutes(5),
                "maximumFailureDelay");
        if (drainInterval.compareTo(idleInterval) > 0) {
            throw new IllegalArgumentException("drainInterval must not exceed idleInterval");
        }
    }

    public Duration delay(int consecutiveFailures, boolean fullBatch) {
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException("consecutiveFailures must not be negative");
        }
        if (consecutiveFailures == 0) {
            return fullBatch ? drainInterval : idleInterval;
        }
        Duration delay = initialFailureDelay;
        for (int attempt = 1; attempt < consecutiveFailures
                && delay.compareTo(maximumFailureDelay) < 0; attempt++) {
            if (delay.compareTo(maximumFailureDelay.dividedBy(2)) > 0) {
                return maximumFailureDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximumFailureDelay) > 0 ? maximumFailureDelay : delay;
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " outside reviewed range");
        }
        return value;
    }
}
