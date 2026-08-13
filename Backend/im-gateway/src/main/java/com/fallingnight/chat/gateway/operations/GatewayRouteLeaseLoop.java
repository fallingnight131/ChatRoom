package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.routing.GatewayRouteRegistrationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/** Explicitly activated, non-overlapping gateway boot-lease renewal. */
public final class GatewayRouteLeaseLoop implements AutoCloseable {
    private final BooleanSupplier renew;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Duration lease;
    private final Duration healthyInterval;
    private final Duration initialFailureDelay;
    private final Duration maximumFailureDelay;
    private final LongAdder attempts = new LongAdder();
    private final LongAdder renewed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final AtomicLong nextDelayMillis = new AtomicLong();
    private Cancellable scheduled;
    private Instant expiresAt;
    private int consecutiveFailures;
    private boolean started;
    private boolean closed;

    public GatewayRouteLeaseLoop(GatewayRouteRegistrationService registration,
            ScheduledExecutorService scheduler, Clock clock, Duration lease,
            Duration healthyInterval, Duration initialFailureDelay,
            Duration maximumFailureDelay) {
        this(registration::renewGateway, (task, delay) -> {
            ScheduledFuture<?> future = scheduler.schedule(
                    task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, clock, lease, healthyInterval, initialFailureDelay, maximumFailureDelay);
        Objects.requireNonNull(registration, "registration");
        Objects.requireNonNull(scheduler, "scheduler");
    }

    public GatewayRouteLeaseLoop(BooleanSupplier renew,
            ScheduledExecutorService scheduler, Clock clock, Duration lease,
            Duration healthyInterval, Duration initialFailureDelay,
            Duration maximumFailureDelay) {
        this(renew, (task, delay) -> {
            ScheduledFuture<?> future = scheduler.schedule(
                    task, delay.toMillis(), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }, clock, lease, healthyInterval, initialFailureDelay, maximumFailureDelay);
        Objects.requireNonNull(scheduler, "scheduler");
    }

    GatewayRouteLeaseLoop(BooleanSupplier renew, Scheduler scheduler, Clock clock,
            Duration lease, Duration healthyInterval, Duration initialFailureDelay,
            Duration maximumFailureDelay) {
        this.renew = Objects.requireNonNull(renew, "renew");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lease = bounded(lease, GatewayRouteRegistrationService.MIN_LEASE,
                GatewayRouteRegistrationService.MAX_LEASE, "lease");
        this.healthyInterval = bounded(healthyInterval, Duration.ofSeconds(1),
                lease.dividedBy(2), "healthyInterval");
        this.initialFailureDelay = bounded(initialFailureDelay, Duration.ofMillis(100),
                lease.dividedBy(2), "initialFailureDelay");
        this.maximumFailureDelay = bounded(maximumFailureDelay, initialFailureDelay,
                lease.dividedBy(2), "maximumFailureDelay");
    }

    public synchronized void start() {
        if (started || closed) throw new IllegalStateException("gateway route lease loop cannot start");
        started = true;
        try { scheduled = scheduler.schedule(this::run, Duration.ZERO); }
        catch (RuntimeException exception) { started = false; throw exception; }
    }

    private void run() {
        synchronized (this) { if (closed) return; scheduled = null; }
        attempts.increment();
        boolean success;
        try { success = renew.getAsBoolean(); }
        catch (RuntimeException exception) { success = false; }
        synchronized (this) {
            Instant now = clock.instant();
            Duration delay;
            if (success) {
                renewed.increment(); consecutiveFailures = 0;
                expiresAt = now.plus(lease); delay = healthyInterval;
            } else {
                failed.increment(); consecutiveFailures = Math.min(64, consecutiveFailures + 1);
                delay = failureDelay(consecutiveFailures);
                if (expiresAt != null && now.isBefore(expiresAt)) {
                    delay = min(delay, Duration.between(now, expiresAt));
                }
            }
            nextDelayMillis.set(delay.toMillis());
            if (!closed) scheduled = scheduler.schedule(this::run, delay);
        }
    }

    public synchronized GatewayRouteLeaseTelemetrySnapshot snapshot() {
        boolean valid = expiresAt != null && clock.instant().isBefore(expiresAt);
        return new GatewayRouteLeaseTelemetrySnapshot(attempts.sum(), renewed.sum(), failed.sum(),
                consecutiveFailures, valid, nextDelayMillis.get());
    }

    @Override public synchronized void close() {
        closed = true;
        if (scheduled != null) { scheduled.cancel(); scheduled = null; }
    }

    private Duration failureDelay(int failures) {
        Duration value = initialFailureDelay;
        for (int count = 1; count < failures && value.compareTo(maximumFailureDelay) < 0; count++) {
            value = min(value.multipliedBy(2), maximumFailureDelay);
        }
        return value;
    }
    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
    private static Duration bounded(Duration value, Duration minimum, Duration maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)
            throw new IllegalArgumentException(name + " outside reviewed range");
        return value;
    }
    @FunctionalInterface interface Scheduler { Cancellable schedule(Runnable task, Duration delay); }
    @FunctionalInterface interface Cancellable { void cancel(); }
}
