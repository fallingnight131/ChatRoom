package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.profile.ProfileImageCleanupReport;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProfileImageCleanupLoopTest {
    @Test void runsWithoutOverlapBacksOffReportsFixedCountersAndResets() {
        AtomicInteger runs = new AtomicInteger(); ManualScheduler scheduler = new ManualScheduler();
        ProfileImageCleanupTelemetry telemetry = new ProfileImageCleanupTelemetry();
        var loop = new ProfileImageCleanupLoop(() -> switch (runs.getAndIncrement()) {
            case 0 -> new ProfileImageCleanupReport(2, 2, 0, 0);
            case 1 -> new ProfileImageCleanupReport(1, 0, 1, 0);
            case 2 -> throw new IllegalStateException("database down");
            default -> new ProfileImageCleanupReport(0, 0, 0, 0);
        }, scheduler, backoff(), telemetry);

        loop.start(); assertEquals(Duration.ZERO, scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofMinutes(1), scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofSeconds(2), scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofSeconds(4), scheduler.lastDelay());
        scheduler.runNext(); assertEquals(Duration.ofMinutes(1), scheduler.lastDelay());
        var snapshot = telemetry.snapshot();
        assertEquals(4, snapshot.runs()); assertEquals(1, snapshot.runFailures());
        assertEquals(3, snapshot.claimed()); assertEquals(2, snapshot.deleted());
        assertEquals(1, snapshot.providerFailures()); assertEquals(0, snapshot.consecutiveFailures());
        loop.close();
    }

    @Test void cancelsPendingTaskAndRejectsRepeatedStart() {
        ManualScheduler scheduler = new ManualScheduler();
        var loop = new ProfileImageCleanupLoop(
                () -> new ProfileImageCleanupReport(0, 0, 0, 0),
                scheduler, backoff(), new ProfileImageCleanupTelemetry());
        loop.start(); assertThrows(IllegalStateException.class, loop::start); loop.close();
        assertTrue(scheduler.pending.getLast().cancelled);
        assertThrows(IllegalStateException.class, loop::start);
    }

    private static ProfileImageCleanupBackoff backoff() {
        return new ProfileImageCleanupBackoff(
                Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofSeconds(16));
    }
    private static final class ManualScheduler implements ProfileImageCleanupLoop.Scheduler {
        private final Deque<Scheduled> pending = new ArrayDeque<>();
        private final List<Duration> delays = new ArrayList<>();
        @Override public ProfileImageCleanupLoop.Cancellable schedule(
                Runnable task, Duration delay) {
            Scheduled value = new Scheduled(task); pending.addLast(value); delays.add(delay);
            return () -> value.cancelled = true;
        }
        private void runNext() { Scheduled value = pending.removeFirst(); if (!value.cancelled) value.task.run(); }
        private Duration lastDelay() { return delays.getLast(); }
    }
    private static final class Scheduled {
        private final Runnable task; private boolean cancelled;
        private Scheduled(Runnable task) { this.task = task; }
    }
}
