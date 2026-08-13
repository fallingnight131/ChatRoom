package com.fallingnight.chat.gateway.transport;

import java.time.Duration;
import java.util.Objects;

/** Bounded process-local account admission limits for message forwarding. */
public record MessageForwardAdmissionLimits(
        Duration window, int attemptsPerAccount, int maximumTrackedAccounts) {
    public MessageForwardAdmissionLimits {
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative() || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("forward admission window must be 1ms..1h");
        }
        if (attemptsPerAccount < 1 || attemptsPerAccount > 10_000) {
            throw new IllegalArgumentException("forward attempts must be 1..10000");
        }
        if (maximumTrackedAccounts < 16 || maximumTrackedAccounts > 1_000_000) {
            throw new IllegalArgumentException("forward admission keys must be 16..1000000");
        }
    }
}
