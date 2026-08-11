package com.fallingnight.chat.gateway.transport;

import java.time.Duration;
import java.util.Objects;

/** Explicit bounded configuration for process-local authentication windows. */
public record AuthenticationAdmissionLimits(
        Duration window,
        int gatewayAttempts,
        int directPeerAttempts,
        int accountAttempts,
        int maxTrackedKeys) {
    public AuthenticationAdmissionLimits {
        Objects.requireNonNull(window, "window");
        long windowMs = window.toMillis();
        if (windowMs < 1_000 || windowMs > 3_600_000) {
            throw new IllegalArgumentException("window must be in 1s..1h");
        }
        requireRange(gatewayAttempts, 1, 1_000_000, "gatewayAttempts");
        requireRange(directPeerAttempts, 1, 100_000, "directPeerAttempts");
        requireRange(accountAttempts, 1, 10_000, "accountAttempts");
        requireRange(maxTrackedKeys, 16, 1_000_000, "maxTrackedKeys");
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be in " + minimum + ".." + maximum);
        }
    }
}
