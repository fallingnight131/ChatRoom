package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.messaging.ConversationEventRelayReport;
import com.fallingnight.chat.application.messaging.ConversationEventRelayService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Explicitly activated, non-overlapping relay loop with bounded failure backoff. */
public final class ConversationEventRelayLoop implements AutoCloseable {
    private final Supplier<ConversationEventRelayReport> relay;
    private final Scheduler scheduler;
    private final ConversationEventRelayBackoff backoff;
    private final ConversationEventRelayTelemetry telemetry;
    private final int batchSize;
    private Cancellable scheduled;
    private int consecutiveFailures;
    private boolean started;
    private boolean closed;

    public ConversationEventRelayLoop(ConversationEventRelayService relay,
            ScheduledExecutorService scheduler, ConversationEventRelayBackoff backoff,
            ConversationEventRelayTelemetry telemetry, int batchSize) {
        this(relay::runOnce, (task, delay) -> {
            ScheduledFuture<?> future = scheduler.schedule(
                    task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, backoff, telemetry, batchSize);
        Objects.requireNonNull(relay, "relay");
        Objects.requireNonNull(scheduler, "scheduler");
    }

    ConversationEventRelayLoop(Supplier<ConversationEventRelayReport> relay,
            Scheduler scheduler, ConversationEventRelayBackoff backoff,
            ConversationEventRelayTelemetry telemetry, int batchSize) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (batchSize < 1 || batchSize > ConversationEventRelayService.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be in 1..100");
        }
        this.batchSize = batchSize;
    }

    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("conversation event relay loop cannot be started");
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
        ConversationEventRelayReport report;
        try {
            report = relay.get();
        } catch (RuntimeException exception) {
            completeFailure(null);
            return;
        }
        if (report.deferred() > 0 || report.ownershipLost() > 0) {
            completeFailure(report);
            return;
        }
        synchronized (this) {
            consecutiveFailures = 0;
            Duration delay = backoff.delay(0, report.claimed() == batchSize);
            telemetry.completed(report, 0, delay);
            scheduleUnlessClosed(delay);
        }
    }

    private void completeFailure(ConversationEventRelayReport report) {
        synchronized (this) {
            consecutiveFailures = Math.min(consecutiveFailures + 1, 64);
            Duration delay = backoff.delay(consecutiveFailures, false);
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
