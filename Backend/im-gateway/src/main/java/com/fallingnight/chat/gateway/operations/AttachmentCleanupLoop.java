package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.attachment.AttachmentCleanupReport;
import com.fallingnight.chat.application.attachment.AttachmentCleanupService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Manually activated, non-overlapping attachment cleanup loop with bounded backoff. */
public final class AttachmentCleanupLoop implements AutoCloseable {
    private final Supplier<AttachmentCleanupReport> cleanup;
    private final Scheduler scheduler;
    private final AttachmentCleanupBackoff backoff;
    private final AttachmentCleanupTelemetry telemetry;
    private Cancellable scheduled;
    private int consecutiveFailures;
    private boolean started;
    private boolean closed;

    public AttachmentCleanupLoop(
            AttachmentCleanupService cleanup,
            ScheduledExecutorService scheduler,
            AttachmentCleanupBackoff backoff,
            AttachmentCleanupTelemetry telemetry) {
        this(cleanup::runOnce, (task, delay) -> {
            ScheduledFuture<?> future = scheduler.schedule(
                    task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, backoff, telemetry);
        Objects.requireNonNull(cleanup, "cleanup");
        Objects.requireNonNull(scheduler, "scheduler");
    }

    AttachmentCleanupLoop(
            Supplier<AttachmentCleanupReport> cleanup,
            Scheduler scheduler,
            AttachmentCleanupBackoff backoff,
            AttachmentCleanupTelemetry telemetry) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("attachment cleanup loop cannot be started");
        }
        started = true;
        try {
            scheduled = scheduler.schedule(this::run, Duration.ZERO);
        } catch (RuntimeException exception) {
            started = false;
            throw exception;
        }
    }

    private void run() {
        synchronized (this) {
            if (closed) {
                return;
            }
            scheduled = null;
        }
        AttachmentCleanupReport report;
        try {
            report = cleanup.get();
        } catch (RuntimeException exception) {
            completeFailure(null);
            return;
        }
        boolean failed = report.providerFailures() > 0 || report.confirmationFailures() > 0;
        if (failed) {
            completeFailure(report);
            return;
        }
        synchronized (this) {
            consecutiveFailures = 0;
            Duration delay = backoff.delay(0);
            telemetry.completed(report, 0, delay);
            scheduleUnlessClosed(delay);
        }
    }

    private void completeFailure(AttachmentCleanupReport report) {
        synchronized (this) {
            consecutiveFailures = Math.min(consecutiveFailures + 1, 64);
            Duration delay = backoff.delay(consecutiveFailures);
            if (report == null) {
                telemetry.failed(consecutiveFailures, delay);
            } else {
                telemetry.completed(report, consecutiveFailures, delay);
            }
            scheduleUnlessClosed(delay);
        }
    }

    private void scheduleUnlessClosed(Duration delay) {
        if (!closed) {
            scheduled = scheduler.schedule(this::run, delay);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (scheduled != null) {
            scheduled.cancel();
            scheduled = null;
        }
    }

    @FunctionalInterface
    interface Scheduler {
        Cancellable schedule(Runnable task, Duration delay);
    }

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
