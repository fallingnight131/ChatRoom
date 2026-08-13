package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.routing.GatewayLiveEventConsumerReport;
import com.fallingnight.chat.application.routing.GatewayLiveEventConsumerService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Explicitly activated, non-overlapping per-boot Redis hint consumer. */
public final class GatewayLiveEventConsumerLoop implements AutoCloseable {
    private final Function<String, GatewayLiveEventConsumerReport> consume;
    private final Scheduler scheduler;
    private final GatewayLiveEventConsumerTelemetry telemetry;
    private final int batchSize;
    private final Duration idleInterval, initialFailureDelay, maximumFailureDelay;
    private String cursor = "0-0";
    private int consecutiveFailures;
    private Cancellable scheduled;
    private boolean started, closed;

    public GatewayLiveEventConsumerLoop(GatewayLiveEventConsumerService consumer,
            ScheduledExecutorService scheduler, GatewayLiveEventConsumerTelemetry telemetry,
            int batchSize, Duration idleInterval, Duration initialFailureDelay,
            Duration maximumFailureDelay) {
        this(consumer::runOnce, (task, delay) -> {
            ScheduledFuture<?> future = scheduler.schedule(
                    task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, telemetry, batchSize, idleInterval, initialFailureDelay, maximumFailureDelay);
        Objects.requireNonNull(consumer, "consumer"); Objects.requireNonNull(scheduler, "scheduler");
    }

    GatewayLiveEventConsumerLoop(Function<String, GatewayLiveEventConsumerReport> consume,
            Scheduler scheduler, GatewayLiveEventConsumerTelemetry telemetry, int batchSize,
            Duration idleInterval, Duration initialFailureDelay, Duration maximumFailureDelay) {
        this.consume = Objects.requireNonNull(consume, "consume");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (batchSize < 1 || batchSize > GatewayLiveEventConsumerService.MAX_BATCH_SIZE)
            throw new IllegalArgumentException("batchSize must be in 1..1000");
        this.batchSize = batchSize;
        this.idleInterval = bounded(idleInterval, Duration.ofMillis(10),
                Duration.ofSeconds(10), "idleInterval");
        this.initialFailureDelay = bounded(initialFailureDelay, Duration.ofMillis(100),
                Duration.ofMinutes(1), "initialFailureDelay");
        this.maximumFailureDelay = bounded(maximumFailureDelay, initialFailureDelay,
                Duration.ofMinutes(5), "maximumFailureDelay");
    }

    public synchronized void start() {
        if (started || closed) throw new IllegalStateException("gateway event consumer cannot start");
        started = true;
        try { scheduled = scheduler.schedule(this::run, Duration.ZERO); }
        catch (RuntimeException exception) { started = false; throw exception; }
    }
    private void run() {
        String requested;
        synchronized (this) { if (closed) return; scheduled = null; requested = cursor; }
        GatewayLiveEventConsumerReport report;
        try { report = consume.apply(requested); }
        catch (RuntimeException exception) { completeFailure(null); return; }
        synchronized (this) {
            if (!report.nextStreamId().matches("[0-9]+-[0-9]+"))
                throw new IllegalStateException("consumer returned invalid stream cursor");
            cursor = report.nextStreamId();
            if (report.failed() > 0) { completeFailureLocked(report); return; }
            consecutiveFailures = 0;
            Duration delay = report.read() == batchSize ? Duration.ZERO : idleInterval;
            telemetry.completed(report, 0, delay); scheduleUnlessClosed(delay);
        }
    }
    private void completeFailure(GatewayLiveEventConsumerReport report) {
        synchronized (this) { completeFailureLocked(report); }
    }
    private void completeFailureLocked(GatewayLiveEventConsumerReport report) {
        consecutiveFailures = Math.min(64, consecutiveFailures + 1);
        Duration delay = failureDelay(consecutiveFailures);
        if (report == null) telemetry.failed(consecutiveFailures, delay);
        else telemetry.completed(report, consecutiveFailures, delay);
        scheduleUnlessClosed(delay);
    }
    private void scheduleUnlessClosed(Duration delay) {
        if (!closed) scheduled = scheduler.schedule(this::run, delay);
    }
    public synchronized String cursor() { return cursor; }
    @Override public synchronized void close() {
        closed = true; if (scheduled != null) { scheduled.cancel(); scheduled = null; }
    }
    private Duration failureDelay(int failures) {
        Duration delay = initialFailureDelay;
        for (int count = 1; count < failures && delay.compareTo(maximumFailureDelay) < 0; count++)
            delay = delay.multipliedBy(2).compareTo(maximumFailureDelay) > 0
                    ? maximumFailureDelay : delay.multipliedBy(2);
        return delay;
    }
    private static Duration bounded(Duration value, Duration min, Duration max, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0)
            throw new IllegalArgumentException(name + " outside reviewed range");
        return value;
    }
    @FunctionalInterface interface Scheduler { Cancellable schedule(Runnable task, Duration delay); }
    @FunctionalInterface interface Cancellable { void cancel(); }
}
