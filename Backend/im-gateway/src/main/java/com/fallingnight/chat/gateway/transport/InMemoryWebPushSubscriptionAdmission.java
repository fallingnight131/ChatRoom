package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.application.notification.WebPushSubscriptionAdmissionDecision;
import com.fallingnight.chat.application.notification.WebPushSubscriptionAdmissionPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionMutationAction;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Per-process bounded fixed-window admission keyed by account, installation, and action. */
public final class InMemoryWebPushSubscriptionAdmission
        implements WebPushSubscriptionAdmissionPort {
    private final WebPushSubscriptionAdmissionLimits limits;
    private final Map<Key, Bucket> buckets = new HashMap<>();
    private Instant lastCleanup = Instant.MIN;

    public InMemoryWebPushSubscriptionAdmission(WebPushSubscriptionAdmissionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public synchronized WebPushSubscriptionAdmissionDecision admit(
            UUID accountId,
            UUID installationId,
            WebPushSubscriptionMutationAction action,
            Instant observedAt) {
        Key key = new Key(
                Objects.requireNonNull(accountId, "accountId"),
                Objects.requireNonNull(installationId, "installationId"),
                Objects.requireNonNull(action, "action"));
        Objects.requireNonNull(observedAt, "observedAt");
        cleanup(observedAt);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= limits.maximumTrackedKeys()) {
                return new WebPushSubscriptionAdmissionDecision.RateLimited(limits.window());
            }
            bucket = new Bucket(observedAt);
            buckets.put(key, bucket);
        }
        Duration elapsed = Duration.between(bucket.startedAt, observedAt);
        if (elapsed.isNegative() || elapsed.compareTo(limits.window()) >= 0) {
            bucket.startedAt = observedAt;
            bucket.attempts = 0;
            elapsed = Duration.ZERO;
        }
        if (bucket.attempts >= limits.attemptsPerKey()) {
            Duration retryAfter = limits.window().minus(elapsed);
            return new WebPushSubscriptionAdmissionDecision.RateLimited(
                    retryAfter.isZero() || retryAfter.isNegative()
                            ? Duration.ofMillis(1) : retryAfter);
        }
        bucket.attempts++;
        return WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE;
    }

    public synchronized int trackedKeys() {
        return buckets.size();
    }

    private void cleanup(Instant now) {
        Duration sinceCleanup = Duration.between(lastCleanup, now);
        if (!sinceCleanup.isNegative() && sinceCleanup.compareTo(limits.window()) < 0) return;
        lastCleanup = now;
        buckets.values().removeIf(bucket -> {
            Duration age = Duration.between(bucket.startedAt, now);
            return age.isNegative() || age.compareTo(limits.window()) >= 0;
        });
    }

    private record Key(
            UUID accountId, UUID installationId, WebPushSubscriptionMutationAction action) { }

    private static final class Bucket {
        private Instant startedAt;
        private int attempts;

        private Bucket(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
