package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.routing.GatewayLiveEventConsumerReport;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class GatewayLiveEventConsumerLoopTest {
    @Test void drainsFullBatchRetainsFailedCursorBacksOffAndResets() {
        AtomicInteger calls = new AtomicInteger(); ManualScheduler scheduler = new ManualScheduler();
        GatewayLiveEventConsumerTelemetry telemetry = new GatewayLiveEventConsumerTelemetry();
        var loop = new GatewayLiveEventConsumerLoop(cursor -> switch (calls.getAndIncrement()) {
            case 0 -> { assertEquals("0-0", cursor); yield new GatewayLiveEventConsumerReport(10, 10, 0, 0, 0, "10-0"); }
            case 1 -> { assertEquals("10-0", cursor); yield new GatewayLiveEventConsumerReport(2, 1, 0, 0, 1, "11-0"); }
            case 2 -> { assertEquals("11-0", cursor); throw new IllegalStateException(); }
            default -> { assertEquals("11-0", cursor); yield new GatewayLiveEventConsumerReport(0, 0, 0, 0, 0, "11-0"); }
        }, scheduler, telemetry, 10, Duration.ofMillis(50),
                Duration.ofMillis(100), Duration.ofSeconds(1));
        loop.start(); scheduler.runNext(); assertEquals(Duration.ZERO, scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofMillis(100), scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofMillis(200), scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofMillis(50), scheduler.lastDelay());
        assertEquals("11-0", loop.cursor());
        var snapshot = telemetry.snapshot();
        assertEquals(4, snapshot.runs()); assertEquals(1, snapshot.runFailures());
        assertEquals(12, snapshot.read()); assertEquals(11, snapshot.applied());
        assertEquals(1, snapshot.failed()); assertEquals(0, snapshot.consecutiveFailures());
        loop.close();
    }

    @Test void rejectsRepeatedStartAndCancelsPendingTask() {
        ManualScheduler scheduler = new ManualScheduler();
        var loop = new GatewayLiveEventConsumerLoop(cursor ->
                new GatewayLiveEventConsumerReport(0,0,0,0,0,cursor), scheduler,
                new GatewayLiveEventConsumerTelemetry(), 10, Duration.ofMillis(50),
                Duration.ofMillis(100), Duration.ofSeconds(1));
        loop.start(); assertThrows(IllegalStateException.class, loop::start); loop.close();
        assertTrue(scheduler.pending.getLast().cancelled);
    }
    private static final class ManualScheduler implements GatewayLiveEventConsumerLoop.Scheduler {
        private final Deque<Scheduled> pending = new ArrayDeque<>(); private final List<Duration> delays = new ArrayList<>();
        @Override public GatewayLiveEventConsumerLoop.Cancellable schedule(Runnable task, Duration delay) {
            Scheduled value = new Scheduled(task); pending.addLast(value); delays.add(delay); return () -> value.cancelled = true;
        }
        private void runNext() { Scheduled value = pending.removeFirst(); if (!value.cancelled) value.task.run(); }
        private Duration lastDelay() { return delays.getLast(); }
    }
    private static final class Scheduled { private final Runnable task; private boolean cancelled; private Scheduled(Runnable task) { this.task = task; } }
}
