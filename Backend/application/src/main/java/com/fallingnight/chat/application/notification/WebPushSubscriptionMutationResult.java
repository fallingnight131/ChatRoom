package com.fallingnight.chat.application.notification;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Transport-neutral, fixed-cardinality subscription mutation result. */
public record WebPushSubscriptionMutationResult(
        Outcome outcome,
        Optional<Duration> retryAfter) {
    public WebPushSubscriptionMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        if ((outcome == Outcome.RATE_LIMITED) != retryAfter.isPresent()) {
            throw new IllegalArgumentException("retryAfter is required only for RATE_LIMITED");
        }
        retryAfter.ifPresent(value -> {
            if (value.isZero() || value.isNegative()
                    || value.compareTo(
                            WebPushSubscriptionAdmissionDecision.RateLimited.MAX_RETRY_AFTER) > 0) {
                throw new IllegalArgumentException("retryAfter must be in (0, 1h]");
            }
        });
    }

    public static WebPushSubscriptionMutationResult of(Outcome outcome) {
        return new WebPushSubscriptionMutationResult(outcome, Optional.empty());
    }

    public static WebPushSubscriptionMutationResult rateLimited(Duration retryAfter) {
        return new WebPushSubscriptionMutationResult(
                Outcome.RATE_LIMITED, Optional.of(retryAfter));
    }

    public enum Outcome {
        DISABLED,
        REPLACED,
        DELETED,
        UNCHANGED,
        ACCOUNT_UNAVAILABLE,
        LIMIT_REACHED,
        RATE_LIMITED
    }
}
