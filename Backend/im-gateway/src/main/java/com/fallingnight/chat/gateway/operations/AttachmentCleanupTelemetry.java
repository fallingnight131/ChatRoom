package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.attachment.AttachmentCleanupReport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe fixed-label attachment cleanup telemetry without object identity. */
public final class AttachmentCleanupTelemetry {
    private final LongAdder runs = new LongAdder();
    private final LongAdder runFailures = new LongAdder();
    private final LongAdder revoked = new LongAdder();
    private final LongAdder attempted = new LongAdder();
    private final LongAdder deleted = new LongAdder();
    private final LongAdder providerFailures = new LongAdder();
    private final LongAdder confirmationFailures = new LongAdder();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong nextDelaySeconds = new AtomicLong();

    public void completed(
            AttachmentCleanupReport report, int failures, Duration nextDelay) {
        Objects.requireNonNull(report, "report");
        runs.increment();
        revoked.add(report.revoked());
        attempted.add(report.attempted());
        deleted.add(report.deleted());
        providerFailures.add(report.providerFailures());
        confirmationFailures.add(report.confirmationFailures());
        scheduled(failures, nextDelay);
    }

    public void failed(int failures, Duration nextDelay) {
        runs.increment();
        runFailures.increment();
        scheduled(failures, nextDelay);
    }

    public AttachmentCleanupTelemetrySnapshot snapshot() {
        return new AttachmentCleanupTelemetrySnapshot(
                runs.sum(), runFailures.sum(), revoked.sum(), attempted.sum(), deleted.sum(),
                providerFailures.sum(), confirmationFailures.sum(),
                consecutiveFailures.get(), nextDelaySeconds.get());
    }

    private void scheduled(int failures, Duration nextDelay) {
        if (failures < 0) {
            throw new IllegalArgumentException("failures must not be negative");
        }
        Objects.requireNonNull(nextDelay, "nextDelay");
        consecutiveFailures.set(failures);
        nextDelaySeconds.set(nextDelay.toSeconds());
    }
}
