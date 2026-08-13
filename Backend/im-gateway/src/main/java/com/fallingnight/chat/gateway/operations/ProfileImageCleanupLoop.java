package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.profile.ProfileImageCleanupReport;
import com.fallingnight.chat.application.profile.ProfileImageCleanupService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Explicitly activated, non-overlapping cleanup loop with bounded backoff. */
public final class ProfileImageCleanupLoop implements AutoCloseable {
    private final Supplier<ProfileImageCleanupReport> cleanup;
    private final Scheduler scheduler;
    private final ProfileImageCleanupBackoff backoff;
    private final ProfileImageCleanupTelemetry telemetry;
    private Cancellable scheduled;
    private int consecutiveFailures;
    private boolean started;
    private boolean closed;

    public ProfileImageCleanupLoop(ProfileImageCleanupService cleanup,
            ScheduledExecutorService scheduler, ProfileImageCleanupBackoff backoff,
            ProfileImageCleanupTelemetry telemetry) {
        this(cleanup::runOnce, (task, delay) -> {
            ScheduledFuture<?> future = scheduler.schedule(
                    task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, backoff, telemetry);
        Objects.requireNonNull(cleanup, "cleanup"); Objects.requireNonNull(scheduler, "scheduler");
    }

    ProfileImageCleanupLoop(Supplier<ProfileImageCleanupReport> cleanup,
            Scheduler scheduler, ProfileImageCleanupBackoff backoff,
            ProfileImageCleanupTelemetry telemetry) {
        this.cleanup = Objects.requireNonNull(cleanup); this.scheduler = Objects.requireNonNull(scheduler);
        this.backoff = Objects.requireNonNull(backoff); this.telemetry = Objects.requireNonNull(telemetry);
    }

    public synchronized void start() {
        if (started || closed)
            throw new IllegalStateException("profile image cleanup loop cannot be started");
        started = true;
        try { scheduled = scheduler.schedule(this::run, Duration.ZERO); }
        catch (RuntimeException exception) { started = false; throw exception; }
    }

    private void run() {
        synchronized (this) { if (closed) return; scheduled = null; }
        ProfileImageCleanupReport report;
        try { report = cleanup.get(); }
        catch (RuntimeException exception) { completeFailure(null); return; }
        if (report.providerFailures() > 0 || report.confirmationFailures() > 0) {
            completeFailure(report); return;
        }
        synchronized (this) {
            consecutiveFailures = 0; Duration delay = backoff.delay(0);
            telemetry.completed(report, 0, delay); scheduleUnlessClosed(delay);
        }
    }

    private void completeFailure(ProfileImageCleanupReport report) {
        synchronized (this) {
            consecutiveFailures = Math.min(consecutiveFailures + 1, 64);
            Duration delay = backoff.delay(consecutiveFailures);
            if (report == null) telemetry.failed(consecutiveFailures, delay);
            else telemetry.completed(report, consecutiveFailures, delay);
            scheduleUnlessClosed(delay);
        }
    }
    private void scheduleUnlessClosed(Duration delay) {
        if (!closed) scheduled = scheduler.schedule(this::run, delay);
    }
    @Override public synchronized void close() {
        closed = true;
        if (scheduled != null) { scheduled.cancel(); scheduled = null; }
    }

    @FunctionalInterface interface Scheduler {
        Cancellable schedule(Runnable task, Duration delay);
    }
    @FunctionalInterface interface Cancellable { void cancel(); }
}
