package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AuthenticationWorkerPoolTest {
    @Test
    void boundsWorkersAndQueueAndOwnsTheirLifecycle() throws Exception {
        AuthenticationWorkerPool pool = new AuthenticationWorkerPool(
                1, 1, Duration.ofSeconds(1));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch queuedFinished = new CountDownLatch(1);
        AtomicReference<String> workerName = new AtomicReference<>();
        try {
            pool.execute(() -> {
                workerName.set(Thread.currentThread().getName());
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));
            pool.execute(queuedFinished::countDown);
            assertEquals(1, pool.activeCount());
            assertEquals(1, pool.queuedCount());
            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));

            release.countDown();
            assertTrue(queuedFinished.await(2, TimeUnit.SECONDS));
            assertTrue(workerName.get().startsWith("chat-auth-"));
        } finally {
            release.countDown();
            pool.close();
        }
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }));
    }

    @Test
    void rejectsUnsafeCapacityAndShutdownConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticationWorkerPool(0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticationWorkerPool(1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthenticationWorkerPool(1, 1, Duration.ZERO));
    }
}
