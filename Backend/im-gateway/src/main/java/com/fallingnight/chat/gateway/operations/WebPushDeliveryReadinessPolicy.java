package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.notification.WebPushOutboxStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Reviewed thresholds for the optional push component; never gates core chat traffic. */
public final class WebPushDeliveryReadinessPolicy {
    private static final long MAX_PENDING_BOUND = 1_000_000;
    private static final long MAX_EXPIRED_BOUND = 100_000;

    private final long maximumPending;
    private final Duration maximumOldestAge;
    private final long maximumExpired;
    private final int consecutiveFailureThreshold;

    public WebPushDeliveryReadinessPolicy(
            long maximumPending,
            Duration maximumOldestAge,
            long maximumExpired,
            int consecutiveFailureThreshold) {
        if (maximumPending < 1 || maximumPending > MAX_PENDING_BOUND) {
            throw new IllegalArgumentException("maximumPending outside reviewed range");
        }
        this.maximumOldestAge = boundedAge(maximumOldestAge);
        if (maximumExpired < 0 || maximumExpired > MAX_EXPIRED_BOUND) {
            throw new IllegalArgumentException("maximumExpired outside reviewed range");
        }
        if (consecutiveFailureThreshold < 1 || consecutiveFailureThreshold > 64) {
            throw new IllegalArgumentException(
                    "consecutiveFailureThreshold outside reviewed range");
        }
        this.maximumPending = maximumPending;
        this.maximumExpired = maximumExpired;
        this.consecutiveFailureThreshold = consecutiveFailureThreshold;
    }

    public WebPushDeliveryReadiness evaluate(
            WebPushOutboxStatus status,
            WebPushDeliveryLoopTelemetrySnapshot loop,
            Instant observedAt) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(observedAt, "observedAt");
        if (loop.consecutiveFailures() < 0) {
            throw new IllegalArgumentException("negative Web Push loop failure count");
        }
        if (loop.consecutiveFailures() >= consecutiveFailureThreshold) {
            return WebPushDeliveryReadiness.unready(
                    WebPushDeliveryReadiness.Reason.CONSECUTIVE_FAILURES);
        }
        if (status.expired() > maximumExpired) {
            return WebPushDeliveryReadiness.unready(
                    WebPushDeliveryReadiness.Reason.EXPIRED_BACKLOG);
        }
        if (status.pending() > maximumPending) {
            return WebPushDeliveryReadiness.unready(
                    WebPushDeliveryReadiness.Reason.BACKLOG_COUNT);
        }
        if (status.oldestAgeSeconds(observedAt) > maximumOldestAge.toSeconds()) {
            return WebPushDeliveryReadiness.unready(
                    WebPushDeliveryReadiness.Reason.BACKLOG_AGE);
        }
        return WebPushDeliveryReadiness.healthy();
    }

    private static Duration boundedAge(Duration value) {
        Objects.requireNonNull(value, "maximumOldestAge");
        if (value.compareTo(Duration.ofSeconds(1)) < 0
                || value.compareTo(Duration.ofHours(24)) > 0
                || value.getNano() != 0) {
            throw new IllegalArgumentException("maximumOldestAge outside reviewed range");
        }
        return value;
    }
}
