package com.fallingnight.chat.gateway.transport;

import java.time.Duration;
import java.util.Objects;

/** Per-process bounded fixed-window limits for Web Push subscription mutation. */
public record WebPushSubscriptionAdmissionLimits(
        Duration window, int attemptsPerKey, int maximumTrackedKeys) {
    public WebPushSubscriptionAdmissionLimits {
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative() || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("window must be in (0, 1h]");
        }
        if (attemptsPerKey < 1 || attemptsPerKey > 10_000) {
            throw new IllegalArgumentException("attemptsPerKey must be in [1, 10000]");
        }
        if (maximumTrackedKeys < 16 || maximumTrackedKeys > 1_000_000) {
            throw new IllegalArgumentException("maximumTrackedKeys must be in [16, 1000000]");
        }
    }
}
