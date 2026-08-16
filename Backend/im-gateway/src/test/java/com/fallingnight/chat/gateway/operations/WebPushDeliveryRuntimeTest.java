package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushOutboxClaim;
import com.fallingnight.chat.application.notification.WebPushOutboxPort;
import com.fallingnight.chat.application.notification.WebPushTerminalOutcome;
import com.fallingnight.chat.application.notification.WebPushWorkerReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WebPushDeliveryRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void ownsNamedSchedulerAndWorkerAndStopsThemOnClose() throws Exception {
        CountDownLatch claimed = new CountDownLatch(1);
        AtomicReference<String> workerName = new AtomicReference<>();
        StubOutbox outbox = new StubOutbox(() -> {
            workerName.set(Thread.currentThread().getName());
            claimed.countDown();
        });
        WebPushDeliveryRuntime runtime = runtime(outbox, Duration.ofSeconds(1));

        assertFalse(runtime.running());
        runtime.start();
        assertTrue(runtime.running());
        assertTrue(claimed.await(2, TimeUnit.SECONDS));
        assertEquals("chat-web-push-worker", workerName.get());
        assertTrue(runtime.activeWorkers() <= 1);
        assertTrue(runtime.queuedWork() <= 1);
        assertTrue(runtime.scheduledTasks() <= 1);

        runtime.close();
        assertFalse(runtime.running());
        assertThrows(IllegalStateException.class, runtime::start);
        runtime.close();
    }

    @Test
    void interruptsAStuckWorkerAfterTheBoundedGracePeriod() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        StubOutbox outbox = new StubOutbox(() -> {
            entered.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        WebPushDeliveryRuntime runtime = runtime(outbox, Duration.ofMillis(100));
        runtime.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        runtime.close();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertFalse(runtime.running());
    }

    @Test
    void rejectsUnsafeShutdownBoundsAndRepeatedStart() {
        assertThrows(IllegalArgumentException.class,
                () -> runtime(new StubOutbox(() -> { }), Duration.ofMillis(99)));
        WebPushDeliveryRuntime runtime = runtime(
                new StubOutbox(() -> { }), Duration.ofSeconds(1));
        try {
            runtime.start();
            assertThrows(IllegalStateException.class, runtime::start);
        } finally {
            runtime.close();
        }
    }

    private static WebPushDeliveryRuntime runtime(
            WebPushOutboxPort outbox, Duration shutdownTimeout) {
        return new WebPushDeliveryRuntime(
                outbox,
                (claim, observedAt) -> new WebPushWorkerReport(
                        WebPushWorkerReport.Status.COMPLETED, 0, 0, 0, 0, 0),
                Clock.fixed(NOW, ZoneOffset.UTC),
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                Duration.ofSeconds(30), 10,
                new WebPushDeliveryLoopBackoff(
                        Duration.ofMillis(10), Duration.ofSeconds(1),
                        Duration.ofMillis(100), Duration.ofSeconds(1)),
                new WebPushDeliveryLoopTelemetry(), shutdownTimeout);
    }

    private static final class StubOutbox implements WebPushOutboxPort {
        private final Runnable onClaim;

        private StubOutbox(Runnable onClaim) {
            this.onClaim = onClaim;
        }

        @Override
        public List<WebPushOutboxClaim> claim(
                UUID owner, Instant claimedAt, Duration lease, int limit) {
            onClaim.run();
            return List.of();
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
}
