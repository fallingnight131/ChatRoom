package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.profile.ProfileImageCleanupReport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fixed-cardinality cleanup telemetry without object keys or claim IDs. */
public final class ProfileImageCleanupTelemetry {
    private final LongAdder runs = new LongAdder();
    private final LongAdder runFailures = new LongAdder();
    private final LongAdder claimed = new LongAdder();
    private final LongAdder deleted = new LongAdder();
    private final LongAdder providerFailures = new LongAdder();
    private final LongAdder confirmationFailures = new LongAdder();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong nextDelaySeconds = new AtomicLong();

    public void completed(ProfileImageCleanupReport report, int failures, Duration nextDelay) {
        Objects.requireNonNull(report, "report"); runs.increment();
        claimed.add(report.claimed()); deleted.add(report.deleted());
        providerFailures.add(report.providerFailures());
        confirmationFailures.add(report.confirmationFailures());
        scheduled(failures, nextDelay);
    }
    public void failed(int failures, Duration nextDelay) {
        runs.increment(); runFailures.increment(); scheduled(failures, nextDelay);
    }
    public ProfileImageCleanupTelemetrySnapshot snapshot() {
        return new ProfileImageCleanupTelemetrySnapshot(runs.sum(), runFailures.sum(),
                claimed.sum(), deleted.sum(), providerFailures.sum(),
                confirmationFailures.sum(), consecutiveFailures.get(), nextDelaySeconds.get());
    }
    private void scheduled(int failures, Duration delay) {
        if (failures < 0) throw new IllegalArgumentException("failures must not be negative");
        Objects.requireNonNull(delay, "delay"); consecutiveFailures.set(failures);
        nextDelaySeconds.set(delay.toSeconds());
    }
}
