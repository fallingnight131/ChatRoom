package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.attachment.AttachmentCleanupReport;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AttachmentCleanupLoopTest {
    @Test
    void runsWithoutOverlapBacksOffAndResetsAfterRecovery() {
        AtomicInteger runs = new AtomicInteger();
        ManualScheduler scheduler = new ManualScheduler();
        AttachmentCleanupTelemetry telemetry = new AttachmentCleanupTelemetry();
        AttachmentCleanupLoop loop = new AttachmentCleanupLoop(
                () -> switch (runs.getAndIncrement()) {
                    case 0 -> new AttachmentCleanupReport(2, 2, 2, 0, 0);
                    case 1 -> new AttachmentCleanupReport(1, 2, 1, 1, 0);
                    case 2 -> throw new IllegalStateException("database unavailable");
                    default -> new AttachmentCleanupReport(0, 0, 0, 0, 0);
                },
                scheduler,
                backoff(),
                telemetry);

        loop.start();
        assertEquals(List.of(Duration.ZERO), scheduler.delays);
        scheduler.runNext();
        assertEquals(Duration.ofMinutes(1), scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(Duration.ofSeconds(2), scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(Duration.ofSeconds(4), scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(Duration.ofMinutes(1), scheduler.lastDelay());
        assertEquals(1, scheduler.pending());

        AttachmentCleanupTelemetrySnapshot snapshot = telemetry.snapshot();
        assertEquals(4, snapshot.runs());
        assertEquals(1, snapshot.runFailures());
        assertEquals(3, snapshot.revoked());
        assertEquals(4, snapshot.attempted());
        assertEquals(3, snapshot.deleted());
        assertEquals(1, snapshot.providerFailures());
        assertEquals(0, snapshot.consecutiveFailures());
        assertEquals(60, snapshot.nextDelaySeconds());
        loop.close();
    }

    @Test
    void closesPendingTaskAndRejectsRepeatedStart() {
        ManualScheduler scheduler = new ManualScheduler();
        AttachmentCleanupLoop loop = new AttachmentCleanupLoop(
                () -> new AttachmentCleanupReport(0, 0, 0, 0, 0),
                scheduler, backoff(), new AttachmentCleanupTelemetry());

        loop.start();
        assertThrows(IllegalStateException.class, loop::start);
        loop.close();

        assertTrue(scheduler.last().cancelled);
        scheduler.runNext();
        assertEquals(0, scheduler.pending());
        assertThrows(IllegalStateException.class, loop::start);
    }

    @Test
    void capsExponentialBackoffAndValidatesPolicy() {
        AttachmentCleanupBackoff backoff = backoff();
        assertEquals(Duration.ofMinutes(1), backoff.delay(0));
        assertEquals(Duration.ofSeconds(2), backoff.delay(1));
        assertEquals(Duration.ofSeconds(4), backoff.delay(2));
        assertEquals(Duration.ofSeconds(16), backoff.delay(64));
        assertThrows(IllegalArgumentException.class, () -> backoff.delay(-1));
        assertThrows(IllegalArgumentException.class, () -> new AttachmentCleanupBackoff(
                Duration.ofSeconds(9), Duration.ofSeconds(1), Duration.ofSeconds(2)));
    }

    private static AttachmentCleanupBackoff backoff() {
        return new AttachmentCleanupBackoff(
                Duration.ofMinutes(1), Duration.ofSeconds(2), Duration.ofSeconds(16));
    }

    private static final class ManualScheduler implements AttachmentCleanupLoop.Scheduler {
        private final Deque<Scheduled> pending = new ArrayDeque<>();
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public AttachmentCleanupLoop.Cancellable schedule(Runnable task, Duration delay) {
            Scheduled scheduled = new Scheduled(task);
            pending.addLast(scheduled);
            delays.add(delay);
            return () -> scheduled.cancelled = true;
        }

        private void runNext() {
            Scheduled scheduled = pending.removeFirst();
            if (!scheduled.cancelled) {
                scheduled.task.run();
            }
        }

        private int pending() {
            return pending.size();
        }

        private Duration lastDelay() {
            return delays.getLast();
        }

        private Scheduled last() {
            return pending.getLast();
        }
    }

    private static final class Scheduled {
        private final Runnable task;
        private boolean cancelled;

        private Scheduled(Runnable task) {
            this.task = task;
        }
    }
}
