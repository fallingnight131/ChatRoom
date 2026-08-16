package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.notification.WebPushDeliveryWorkerService;
import com.fallingnight.chat.application.notification.WebPushOutboxClaim;
import com.fallingnight.chat.application.notification.WebPushOutboxPort;
import com.fallingnight.chat.application.notification.WebPushWorkerReport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/** Owns the bounded executors and lifecycle for one non-overlapping Web Push loop. */
public final class WebPushDeliveryRuntime implements AutoCloseable {
    private static final Duration MIN_SHUTDOWN_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MAX_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final int WORKER_QUEUE_CAPACITY = 1;

    private final ScheduledThreadPoolExecutor scheduler;
    private final ThreadPoolExecutor worker;
    private final WebPushDeliveryLoop loop;
    private final Duration shutdownTimeout;
    private boolean started;
    private boolean closed;

    public WebPushDeliveryRuntime(
            WebPushOutboxPort outbox,
            WebPushDeliveryWorkerService processor,
            Clock clock,
            UUID owner,
            Duration lease,
            int batchSize,
            WebPushDeliveryLoopBackoff backoff,
            WebPushDeliveryLoopTelemetry telemetry,
            Duration shutdownTimeout) {
        this(outbox, Objects.requireNonNull(processor, "processor")::process,
                clock, owner, lease, batchSize, backoff, telemetry, shutdownTimeout);
    }

    WebPushDeliveryRuntime(
            WebPushOutboxPort outbox,
            BiFunction<WebPushOutboxClaim, Instant, WebPushWorkerReport> processor,
            Clock clock,
            UUID owner,
            Duration lease,
            int batchSize,
            WebPushDeliveryLoopBackoff backoff,
            WebPushDeliveryLoopTelemetry telemetry,
            Duration shutdownTimeout) {
        this.shutdownTimeout = boundedShutdownTimeout(shutdownTimeout);
        scheduler = scheduler();
        worker = worker();
        try {
            loop = new WebPushDeliveryLoop(outbox, processor, clock, owner, lease, batchSize,
                    (task, delay) -> {
                        var future = scheduler.schedule(
                                task, delay.toMillis(), TimeUnit.MILLISECONDS);
                        return () -> future.cancel(false);
                    }, worker, backoff, telemetry);
        } catch (RuntimeException exception) {
            scheduler.shutdownNow();
            worker.shutdownNow();
            throw exception;
        }
    }

    public synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException("Web Push delivery runtime cannot start");
        }
        loop.start();
        started = true;
    }

    public synchronized boolean running() {
        return started && !closed && !scheduler.isShutdown() && !worker.isShutdown();
    }

    public int activeWorkers() {
        return worker.getActiveCount();
    }

    public int queuedWork() {
        return worker.getQueue().size();
    }

    public int scheduledTasks() {
        return scheduler.getQueue().size();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        loop.close();
        scheduler.shutdownNow();
        worker.shutdown();
        boolean schedulerStopped = await(scheduler);
        boolean workerStopped = await(worker);
        if (!workerStopped) {
            worker.shutdownNow();
            workerStopped = await(worker);
        }
        if (!schedulerStopped || !workerStopped) {
            throw new IllegalStateException("Web Push delivery executors did not stop");
        }
    }

    private boolean await(java.util.concurrent.ExecutorService executor) {
        try {
            return executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            scheduler.shutdownNow();
            worker.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Web Push delivery shutdown interrupted", exception);
        }
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, namedFactory("chat-web-push-scheduler"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ThreadPoolExecutor worker() {
        return new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                namedFactory("chat-web-push-worker"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory namedFactory(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(false);
            return thread;
        };
    }

    private static Duration boundedShutdownTimeout(Duration value) {
        Objects.requireNonNull(value, "shutdownTimeout");
        if (value.compareTo(MIN_SHUTDOWN_TIMEOUT) < 0
                || value.compareTo(MAX_SHUTDOWN_TIMEOUT) > 0) {
            throw new IllegalArgumentException("shutdownTimeout outside reviewed range");
        }
        return value;
    }
}
