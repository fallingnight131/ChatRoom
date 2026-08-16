package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.notification.WebPushDeliveryWorkerService;
import com.fallingnight.chat.application.notification.WebPushOutboxClaim;
import com.fallingnight.chat.application.notification.WebPushOutboxPort;
import com.fallingnight.chat.application.notification.WebPushWorkerReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** Explicitly started, off-scheduler, non-overlapping bounded Web Push delivery loop. */
public final class WebPushDeliveryLoop implements AutoCloseable {
    public static final int MAX_BATCH_SIZE = 100;
    public static final Duration MIN_LEASE = Duration.ofSeconds(1);
    public static final Duration MAX_LEASE = Duration.ofMinutes(5);

    private final WebPushOutboxPort outbox;
    private final BiFunction<WebPushOutboxClaim, Instant, WebPushWorkerReport> processor;
    private final Clock clock;
    private final UUID owner;
    private final Duration lease;
    private final int batchSize;
    private final Scheduler scheduler;
    private final Executor worker;
    private final WebPushDeliveryLoopBackoff backoff;
    private final WebPushDeliveryLoopTelemetry telemetry;
    private Cancellable scheduled;
    private int consecutiveFailures;
    private boolean inFlight;
    private boolean started;
    private boolean closed;

    public WebPushDeliveryLoop(
            WebPushOutboxPort outbox,
            WebPushDeliveryWorkerService processor,
            Clock clock,
            UUID owner,
            Duration lease,
            int batchSize,
            ScheduledExecutorService scheduler,
            Executor worker,
            WebPushDeliveryLoopBackoff backoff,
            WebPushDeliveryLoopTelemetry telemetry) {
        this(outbox, processor::process, clock, owner, lease, batchSize,
                (task, delay) -> {
                    ScheduledFuture<?> future = scheduler.schedule(
                            task, delay.toMillis(), TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                }, worker, backoff, telemetry);
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(scheduler, "scheduler");
    }

    WebPushDeliveryLoop(
            WebPushOutboxPort outbox,
            BiFunction<WebPushOutboxClaim, Instant, WebPushWorkerReport> processor,
            Clock clock,
            UUID owner,
            Duration lease,
            int batchSize,
            Scheduler scheduler,
            Executor worker,
            WebPushDeliveryLoopBackoff backoff,
            WebPushDeliveryLoopTelemetry telemetry) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.lease = boundedLease(lease);
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be in 1..100");
        }
        this.batchSize = batchSize;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("Web Push delivery loop cannot be started");
        }
        started = true;
        try {
            scheduled = scheduler.schedule(this::dispatch, Duration.ZERO);
        } catch (RuntimeException exception) {
            started = false;
            throw exception;
        }
    }

    private void dispatch() {
        synchronized (this) {
            if (closed) {
                return;
            }
            scheduled = null;
            if (inFlight) {
                throw new IllegalStateException("Web Push delivery pass overlaps");
            }
            inFlight = true;
        }
        try {
            worker.execute(this::runPass);
        } catch (RuntimeException exception) {
            synchronized (this) {
                inFlight = false;
                completeFailure(true);
            }
        }
    }

    private void runPass() {
        synchronized (this) {
            if (closed) {
                inFlight = false;
                return;
            }
        }
        List<WebPushOutboxClaim> claims;
        try {
            claims = List.copyOf(Objects.requireNonNull(
                    outbox.claim(owner, clock.instant(), lease, batchSize), "claims"));
            if (claims.size() > batchSize) {
                throw new IllegalStateException("Web Push outbox exceeded requested batch");
            }
            if (claims.stream().anyMatch(claim -> !owner.equals(claim.claimOwner()))) {
                throw new IllegalStateException("Web Push outbox returned a foreign claim");
            }
        } catch (RuntimeException exception) {
            synchronized (this) {
                inFlight = false;
                completeFailure(false);
            }
            return;
        }

        int processed = 0;
        int processingFailures = 0;
        int completed = 0;
        int deferred = 0;
        int fenceLost = 0;
        int disabled = 0;
        for (WebPushOutboxClaim claim : claims) {
            try {
                WebPushWorkerReport report = Objects.requireNonNull(
                        processor.apply(claim, clock.instant()), "workerReport");
                processed++;
                switch (report.status()) {
                    case COMPLETED -> completed++;
                    case DEFERRED -> deferred++;
                    case FENCE_LOST -> fenceLost++;
                    case DISABLED -> disabled++;
                }
            } catch (RuntimeException exception) {
                processingFailures++;
            }
        }
        WebPushDeliveryLoopPassReport report = new WebPushDeliveryLoopPassReport(
                claims.size(), processed, processingFailures, completed, deferred,
                fenceLost, disabled);
        synchronized (this) {
            inFlight = false;
            if (processingFailures > 0 || fenceLost > 0 || disabled > 0) {
                completeFailure(report);
                return;
            }
            consecutiveFailures = 0;
            Duration delay = backoff.delay(0, claims.size() == batchSize);
            telemetry.completed(report, 0, delay);
            scheduleUnlessClosed(delay);
        }
    }

    private void completeFailure(boolean workerRejected) {
        consecutiveFailures = Math.min(consecutiveFailures + 1, 64);
        Duration delay = backoff.delay(consecutiveFailures, false);
        if (workerRejected) {
            telemetry.workerRejected(consecutiveFailures, delay);
        } else {
            telemetry.failed(consecutiveFailures, delay);
        }
        scheduleUnlessClosed(delay);
    }

    private void completeFailure(WebPushDeliveryLoopPassReport report) {
        consecutiveFailures = Math.min(consecutiveFailures + 1, 64);
        Duration delay = backoff.delay(consecutiveFailures, false);
        telemetry.completed(report, consecutiveFailures, delay);
        scheduleUnlessClosed(delay);
    }

    private void scheduleUnlessClosed(Duration delay) {
        if (!closed) {
            scheduled = scheduler.schedule(this::dispatch, delay);
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

    private static Duration boundedLease(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        if (lease.compareTo(MIN_LEASE) < 0 || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("lease outside reviewed range");
        }
        return lease;
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
