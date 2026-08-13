package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.gateway.operations.EventLoopSnapshot;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Lifecycle-bound fixed-cadence lag probes for Netty worker event loops. */
@SuppressWarnings("deprecation") // Netty 4.1 pins NIO ownership to NioEventLoopGroup.
final class EventLoopMonitor implements AutoCloseable {
    private final long intervalNanos;
    private final AtomicBoolean available = new AtomicBoolean();
    private final AtomicLong maximumLagNanos = new AtomicLong();
    private final LongAdder samples = new LongAdder();
    private List<SingleThreadEventExecutor> executors = List.of();
    private AtomicLongArray latestLagNanos = new AtomicLongArray(0);
    private AtomicLongArray expectedRunNanos = new AtomicLongArray(0);
    private List<ScheduledFuture<?>> probes = List.of();

    EventLoopMonitor(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("event-loop probe interval must be positive");
        }
        intervalNanos = interval.toNanos();
    }

    synchronized void start(NioEventLoopGroup group) {
        Objects.requireNonNull(group, "group");
        if (available.get()) throw new IllegalStateException("event-loop monitor already started");
        List<SingleThreadEventExecutor> workers = new ArrayList<>();
        for (EventExecutor executor : group) {
            if (!(executor instanceof SingleThreadEventExecutor worker)) {
                throw new IllegalArgumentException("unsupported Netty event-loop executor");
            }
            workers.add(worker);
        }
        if (workers.isEmpty()) throw new IllegalArgumentException("event-loop group is empty");
        executors = List.copyOf(workers);
        latestLagNanos = new AtomicLongArray(workers.size());
        expectedRunNanos = new AtomicLongArray(workers.size());
        List<ScheduledFuture<?>> scheduled = new ArrayList<>();
        for (int index = 0; index < workers.size(); index++) {
            int workerIndex = index;
            long firstRun = System.nanoTime() + intervalNanos;
            expectedRunNanos.set(workerIndex, firstRun);
            scheduled.add(workers.get(index).scheduleAtFixedRate(
                    () -> record(workerIndex), intervalNanos, intervalNanos,
                    TimeUnit.NANOSECONDS));
        }
        probes = List.copyOf(scheduled);
        available.set(true);
    }

    synchronized EventLoopSnapshot snapshot() {
        if (!available.get()) return EventLoopSnapshot.unavailable();
        long latestMaximum = 0;
        long pending = 0;
        List<SingleThreadEventExecutor> current = executors;
        AtomicLongArray latest = latestLagNanos;
        for (int index = 0; index < current.size(); index++) {
            latestMaximum = Math.max(latestMaximum, latest.get(index));
            pending += current.get(index).pendingTasks();
        }
        return new EventLoopSnapshot(
                true, current.size(), samples.sum(), latestMaximum,
                maximumLagNanos.get(), pending);
    }

    private void record(int index) {
        long expected = expectedRunNanos.getAndAdd(index, intervalNanos);
        long lag = Math.max(0, System.nanoTime() - expected);
        latestLagNanos.set(index, lag);
        maximumLagNanos.accumulateAndGet(lag, Math::max);
        samples.increment();
    }

    @Override
    public synchronized void close() {
        if (!available.getAndSet(false)) return;
        probes.forEach(probe -> probe.cancel(false));
        probes = List.of();
        executors = List.of();
    }
}
