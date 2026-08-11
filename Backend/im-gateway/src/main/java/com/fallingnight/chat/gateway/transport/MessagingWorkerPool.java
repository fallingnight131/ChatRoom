package com.fallingnight.chat.gateway.transport;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Gateway-owned bounded executor isolated from password and session work. */
public final class MessagingWorkerPool implements Executor, AutoCloseable {
    private static final int MAX_WORKERS = 64;
    private static final int MAX_QUEUE_CAPACITY = 100_000;

    private final ThreadPoolExecutor executor;
    private final Duration closeTimeout;

    public MessagingWorkerPool(int workerCount, int queueCapacity, Duration closeTimeout) {
        if (workerCount < 1 || workerCount > MAX_WORKERS) {
            throw new IllegalArgumentException("workerCount must be in 1..64");
        }
        if (queueCapacity < 1 || queueCapacity > MAX_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("queueCapacity must be in 1..100000");
        }
        this.closeTimeout = requirePositive(closeTimeout);
        executor = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void execute(Runnable command) throws RejectedExecutionException {
        executor.execute(Objects.requireNonNull(command, "command"));
    }

    public int activeCount() {
        return executor.getActiveCount();
    }

    public int queuedCount() {
        return executor.getQueue().size();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "chat-message-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "closeTimeout");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("closeTimeout must be positive");
        }
        return value;
    }
}
