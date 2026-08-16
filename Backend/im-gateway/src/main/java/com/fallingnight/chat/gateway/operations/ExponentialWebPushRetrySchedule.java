package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.notification.WebPushOutboxClaim;
import com.fallingnight.chat.application.notification.WebPushRetrySchedulePort;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/** Exponential bounded retry with 50%-150% jitter and strict event-expiry clipping. */
public final class ExponentialWebPushRetrySchedule implements WebPushRetrySchedulePort {
    public static final Duration BASE_DELAY = Duration.ofSeconds(1);
    public static final Duration MAX_DELAY = Duration.ofMinutes(15);
    private static final Duration EXPIRY_MARGIN = Duration.ofMillis(1);

    private final LongSupplier jitterPermille;

    public ExponentialWebPushRetrySchedule() {
        this(() -> ThreadLocalRandom.current().nextLong(500, 1_501));
    }

    ExponentialWebPushRetrySchedule(LongSupplier jitterPermille) {
        this.jitterPermille = Objects.requireNonNull(jitterPermille, "jitterPermille");
    }

    @Override
    public Instant nextRetry(WebPushOutboxClaim claim, Instant failedAt) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(failedAt, "failedAt");
        if (failedAt.isBefore(claim.claimedAt()) || failedAt.isAfter(claim.claimExpiresAt())) {
            throw new IllegalArgumentException("retry failure time is outside claim lease");
        }
        long permille = jitterPermille.getAsLong();
        if (permille < 500 || permille > 1_500) {
            throw new IllegalStateException("retry jitter must be in 500..1500 permille");
        }
        int shift = Math.min(claim.attemptCount() - 1, 20);
        long exponentialMillis = Math.min(
                Math.multiplyExact(BASE_DELAY.toMillis(), 1L << shift),
                MAX_DELAY.toMillis());
        long jitteredMillis = Math.max(1L,
                Math.min(MAX_DELAY.toMillis(), exponentialMillis * permille / 1_000L));
        Instant requested = failedAt.plusMillis(jitteredMillis);
        Instant latest = claim.intent().expiresAt().minus(EXPIRY_MARGIN);
        if (!latest.isAfter(failedAt)) {
            throw new IllegalStateException("Web Push event has no retry window");
        }
        return requested.isAfter(latest) ? latest : requested;
    }
}
