package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class GatewayRouteLeaseLoopTest {
    @Test void renewsRetriesBeforeExpiryAndReportsLeaseLoss() {
        MutableClock clock = new MutableClock(); ManualScheduler scheduler = new ManualScheduler(clock);
        AtomicInteger attempts = new AtomicInteger();
        var loop = new GatewayRouteLeaseLoop(() -> attempts.getAndIncrement() == 0,
                scheduler, clock, Duration.ofSeconds(10), Duration.ofSeconds(4),
                Duration.ofSeconds(2), Duration.ofSeconds(4));
        loop.start(); scheduler.runNext();
        assertTrue(loop.snapshot().leaseValid()); assertEquals(Duration.ofSeconds(4), scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofSeconds(2), scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofSeconds(4), scheduler.lastDelay());
        scheduler.runNext(); assertFalse(loop.snapshot().leaseValid());
        assertEquals(4, loop.snapshot().attempts()); assertEquals(1, loop.snapshot().renewed());
        assertEquals(3, loop.snapshot().failed());
        loop.close(); assertTrue(scheduler.pending.getLast().cancelled);
    }

    @Test void rejectsRepeatedStartAndUnsafeRenewalInterval() {
        MutableClock clock = new MutableClock(); ManualScheduler scheduler = new ManualScheduler(clock);
        var loop = new GatewayRouteLeaseLoop(() -> true, scheduler, clock,
                Duration.ofSeconds(10), Duration.ofSeconds(5),
                Duration.ofMillis(100), Duration.ofSeconds(2));
        loop.start(); assertThrows(IllegalStateException.class, loop::start); loop.close();
        assertThrows(IllegalArgumentException.class, () -> new GatewayRouteLeaseLoop(
                () -> true, scheduler, clock, Duration.ofSeconds(10), Duration.ofSeconds(6),
                Duration.ofMillis(100), Duration.ofSeconds(2)));
    }

    private static final class ManualScheduler implements GatewayRouteLeaseLoop.Scheduler {
        private final MutableClock clock; private final Deque<Scheduled> pending = new ArrayDeque<>();
        private final List<Duration> delays = new ArrayList<>();
        private ManualScheduler(MutableClock clock) { this.clock = clock; }
        @Override public GatewayRouteLeaseLoop.Cancellable schedule(Runnable task, Duration delay) {
            Scheduled value = new Scheduled(task, delay); pending.addLast(value); delays.add(delay);
            return () -> value.cancelled = true;
        }
        private void runNext() { Scheduled value = pending.removeFirst(); clock.advance(value.delay); if (!value.cancelled) value.task.run(); }
        private Duration lastDelay() { return delays.getLast(); }
    }
    private static final class Scheduled {
        private final Runnable task; private final Duration delay; private boolean cancelled;
        private Scheduled(Runnable task, Duration delay) { this.task = task; this.delay = delay; }
    }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2030-01-01T00:00:00Z");
        private void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
