package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.messaging.ConversationEventRelayReport;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ConversationEventRelayLoopTest {
    @Test
    void drainsFullBatchThenBacksOffFailuresAndResetsOnIdle() {
        AtomicInteger runs = new AtomicInteger();
        ManualScheduler scheduler = new ManualScheduler();
        ConversationEventRelayTelemetry telemetry = new ConversationEventRelayTelemetry();
        var loop = new ConversationEventRelayLoop(() -> switch (runs.getAndIncrement()) {
            case 0 -> new ConversationEventRelayReport(10, 10, 0, 0, 0);
            case 1 -> new ConversationEventRelayReport(2, 1, 1, 0, 0);
            case 2 -> throw new IllegalStateException("database detail");
            default -> new ConversationEventRelayReport(0, 0, 0, 0, 0);
        }, scheduler, backoff(), telemetry, 10);

        loop.start();
        assertEquals(Duration.ZERO, scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(Duration.ZERO, scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(Duration.ofMillis(100), scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(Duration.ofMillis(200), scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(Duration.ofMillis(50), scheduler.lastDelay());

        var snapshot = telemetry.snapshot();
        assertEquals(4, snapshot.runs());
        assertEquals(1, snapshot.runFailures());
        assertEquals(12, snapshot.claimed());
        assertEquals(11, snapshot.published());
        assertEquals(1, snapshot.deferred());
        assertEquals(0, snapshot.consecutiveFailures());
        assertEquals(50, snapshot.nextDelayMillis());
        loop.close();
    }

    @Test
    void cancelsPendingWorkAndRejectsRepeatedStart() {
        ManualScheduler scheduler = new ManualScheduler();
        var loop = new ConversationEventRelayLoop(
                () -> new ConversationEventRelayReport(0, 0, 0, 0, 0),
                scheduler, backoff(), new ConversationEventRelayTelemetry(), 10);
        loop.start();
        assertThrows(IllegalStateException.class, loop::start);
        loop.close();
        assertTrue(scheduler.pending.getLast().cancelled);
        assertThrows(IllegalStateException.class, loop::start);
    }

    private static ConversationEventRelayBackoff backoff() {
        return new ConversationEventRelayBackoff(
                Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(1));
    }

    private static final class ManualScheduler implements ConversationEventRelayLoop.Scheduler {
        private final Deque<Scheduled> pending = new ArrayDeque<>();
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public ConversationEventRelayLoop.Cancellable schedule(Runnable task, Duration delay) {
            Scheduled value = new Scheduled(task);
            pending.addLast(value);
            delays.add(delay);
            return () -> value.cancelled = true;
        }

        private void runNext() {
            Scheduled value = pending.removeFirst();
            if (!value.cancelled) {
                value.task.run();
            }
        }

        private Duration lastDelay() {
            return delays.getLast();
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
