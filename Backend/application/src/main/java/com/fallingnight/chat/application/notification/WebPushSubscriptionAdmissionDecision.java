package com.fallingnight.chat.application.notification;

import java.time.Duration;
import java.util.Objects;

/** Server-side mutation admission without endpoint or key material. */
public sealed interface WebPushSubscriptionAdmissionDecision {
    enum Allowed implements WebPushSubscriptionAdmissionDecision { INSTANCE }

    record RateLimited(Duration retryAfter) implements WebPushSubscriptionAdmissionDecision {
        public static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

        public RateLimited {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isZero() || retryAfter.isNegative()
                    || retryAfter.compareTo(MAX_RETRY_AFTER) > 0) {
                throw new IllegalArgumentException("retryAfter must be in (0, 1h]");
            }
        }
    }
}
