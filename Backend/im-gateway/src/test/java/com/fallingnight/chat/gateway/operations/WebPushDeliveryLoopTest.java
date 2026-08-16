package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushNotificationIntent;
import com.fallingnight.chat.application.notification.WebPushOutboxClaim;
import com.fallingnight.chat.application.notification.WebPushOutboxPort;
import com.fallingnight.chat.application.notification.WebPushTerminalOutcome;
import com.fallingnight.chat.application.notification.WebPushWorkerReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

final class WebPushDeliveryLoopTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000111");

    @Test
    void dispatchesOffSchedulerWithoutOverlapAndDrainsABoundedFullBatch() {
        StubOutbox outbox = new StubOutbox();
        outbox.claims.addLast(List.of(claim(1), claim(2)));
        outbox.claims.addLast(List.of());
        Deque<WebPushWorkerReport.Status> statuses = new ArrayDeque<>(List.of(
                WebPushWorkerReport.Status.COMPLETED,
                WebPushWorkerReport.Status.DEFERRED));
        ManualScheduler scheduler = new ManualScheduler();
        ManualExecutor worker = new ManualExecutor();
        WebPushDeliveryLoopTelemetry telemetry = new WebPushDeliveryLoopTelemetry();
        var loop = loop(outbox, (claim, observedAt) -> report(statuses.removeFirst()),
                scheduler, worker, telemetry, 2);

        loop.start();
        assertEquals(Duration.ZERO, scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(0, outbox.claimCalls);
        assertEquals(1, worker.pending.size());
        assertTrue(scheduler.pending.isEmpty());

        worker.runNext();
        assertEquals(1, outbox.claimCalls);
        assertEquals(Duration.ofMillis(10), scheduler.lastDelay());
        scheduler.runNext();
        assertEquals(1, outbox.claimCalls);
        worker.runNext();
        assertEquals(2, outbox.claimCalls);
        assertEquals(Duration.ofSeconds(1), scheduler.lastDelay());

        WebPushDeliveryLoopTelemetrySnapshot snapshot = telemetry.snapshot();
        assertEquals(2, snapshot.runs());
        assertEquals(2, snapshot.claimed());
        assertEquals(2, snapshot.processed());
        assertEquals(1, snapshot.completed());
        assertEquals(1, snapshot.deferred());
        assertEquals(0, snapshot.consecutiveFailures());
        loop.close();
    }

    @Test
    void isolatesClaimProcessingFailureThenBacksOffAndRecovers() {
        StubOutbox outbox = new StubOutbox();
        outbox.claims.addLast(List.of(claim(1), claim(2)));
        outbox.claims.addLast(List.of());
        ManualScheduler scheduler = new ManualScheduler();
        ManualExecutor worker = new ManualExecutor();
        WebPushDeliveryLoopTelemetry telemetry = new WebPushDeliveryLoopTelemetry();
        int[] calls = {0};
        var loop = loop(outbox, (claim, observedAt) -> {
            if (calls[0]++ == 0) throw new IllegalStateException("provider detail");
            return report(WebPushWorkerReport.Status.COMPLETED);
        }, scheduler, worker, telemetry, 10);

        loop.start(); scheduler.runNext(); worker.runNext();
        assertEquals(2, calls[0]);
        assertEquals(Duration.ofMillis(100), scheduler.lastDelay());
        assertEquals(1, telemetry.snapshot().processingFailures());
        assertEquals(1, telemetry.snapshot().consecutiveFailures());

        scheduler.runNext(); worker.runNext();
        assertEquals(Duration.ofSeconds(1), scheduler.lastDelay());
        assertEquals(0, telemetry.snapshot().consecutiveFailures());
        loop.close();
    }

    @Test
    void backsOffWorkerRejectionAndCancelsPendingScheduleOnClose() {
        StubOutbox outbox = new StubOutbox();
        ManualScheduler scheduler = new ManualScheduler();
        Executor rejectingWorker = task -> { throw new IllegalStateException("saturated"); };
        WebPushDeliveryLoopTelemetry telemetry = new WebPushDeliveryLoopTelemetry();
        var loop = loop(outbox, (claim, observedAt) -> report(
                WebPushWorkerReport.Status.COMPLETED), scheduler, rejectingWorker,
                telemetry, 10);

        loop.start(); scheduler.runNext();
        assertEquals(0, outbox.claimCalls);
        assertEquals(1, telemetry.snapshot().workerRejections());
        assertEquals(Duration.ofMillis(100), scheduler.lastDelay());
        loop.close();
        assertTrue(scheduler.pending.getLast().cancelled);
        assertThrows(IllegalStateException.class, loop::start);

        String metrics = PrometheusWebPushDeliveryLoopMetrics.render(telemetry.snapshot());
        assertTrue(metrics.contains("web_push_delivery_loop_worker_rejections_total 1"));
        assertFalse(metrics.contains("{"));
        assertFalse(metrics.contains("account"));
        assertFalse(metrics.contains("message"));
        assertFalse(metrics.contains("claim_id"));
    }

    @Test
    void rejectsForeignClaimsWithoutCallingTheProcessor() {
        StubOutbox outbox = new StubOutbox();
        WebPushOutboxClaim foreign = claim(1);
        foreign = new WebPushOutboxClaim(
                foreign.intent(), foreign.claimId(), UUID.randomUUID(),
                foreign.claimedAt(), foreign.claimExpiresAt(), foreign.attemptCount());
        outbox.claims.addLast(List.of(foreign));
        ManualScheduler scheduler = new ManualScheduler();
        ManualExecutor worker = new ManualExecutor();
        WebPushDeliveryLoopTelemetry telemetry = new WebPushDeliveryLoopTelemetry();
        int[] processed = {0};
        var loop = loop(outbox, (claim, observedAt) -> {
            processed[0]++;
            return report(WebPushWorkerReport.Status.COMPLETED);
        }, scheduler, worker, telemetry, 10);

        loop.start(); scheduler.runNext(); worker.runNext();

        assertEquals(0, processed[0]);
        assertEquals(1, telemetry.snapshot().runFailures());
        assertEquals(Duration.ofMillis(100), scheduler.lastDelay());
        loop.close();
    }

    private static WebPushDeliveryLoop loop(
            StubOutbox outbox,
            BiFunction<WebPushOutboxClaim, Instant, WebPushWorkerReport> processor,
            ManualScheduler scheduler,
            Executor worker,
            WebPushDeliveryLoopTelemetry telemetry,
            int batchSize) {
        return new WebPushDeliveryLoop(
                outbox, processor, Clock.fixed(NOW, ZoneOffset.UTC), OWNER,
                Duration.ofSeconds(30), batchSize, scheduler, worker, backoff(), telemetry);
    }

    private static WebPushDeliveryLoopBackoff backoff() {
        return new WebPushDeliveryLoopBackoff(
                Duration.ofMillis(10), Duration.ofSeconds(1),
                Duration.ofMillis(100), Duration.ofSeconds(1));
    }

    private static WebPushWorkerReport report(WebPushWorkerReport.Status status) {
        return new WebPushWorkerReport(status, 0, 0, 0, 0, 0);
    }

    private static WebPushOutboxClaim claim(int attempt) {
        var intent = new WebPushNotificationIntent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                NOW.minusSeconds(1), NOW.plusSeconds(60), Set.of());
        return new WebPushOutboxClaim(
                intent, UUID.randomUUID(), OWNER, NOW, NOW.plusSeconds(30), attempt);
    }

    private static final class StubOutbox implements WebPushOutboxPort {
        private final Deque<List<WebPushOutboxClaim>> claims = new ArrayDeque<>();
        private int claimCalls;

        @Override
        public List<WebPushOutboxClaim> claim(
                UUID owner, Instant claimedAt, Duration lease, int limit) {
            claimCalls++;
            return claims.isEmpty() ? List.of() : claims.removeFirst();
        }

        @Override
        public boolean complete(WebPushOutboxClaim claim, Instant completedAt,
                WebPushTerminalOutcome outcome) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean defer(WebPushOutboxClaim claim, Instant failedAt,
                Instant retryAt, String failureCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int expire(Instant observedAt, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purgeCompletedBefore(Instant cutoff, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ManualScheduler implements WebPushDeliveryLoop.Scheduler {
        private final Deque<Scheduled> pending = new ArrayDeque<>();
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public WebPushDeliveryLoop.Cancellable schedule(Runnable task, Duration delay) {
            Scheduled value = new Scheduled(task);
            pending.addLast(value);
            delays.add(delay);
            return () -> value.cancelled = true;
        }

        private void runNext() {
            Scheduled value = pending.removeFirst();
            if (!value.cancelled) value.task.run();
        }

        private Duration lastDelay() {
            return delays.getLast();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> pending = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            pending.addLast(command);
        }

        private void runNext() {
            pending.removeFirst().run();
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
