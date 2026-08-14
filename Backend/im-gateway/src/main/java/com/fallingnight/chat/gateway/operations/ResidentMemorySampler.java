package com.fallingnight.chat.gateway.operations;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Lifecycle-owned cache that keeps native/file reads out of metrics scrapes. */
public final class ResidentMemorySampler implements AutoCloseable {
    public static final Duration MINIMUM_INTERVAL = Duration.ofMillis(250);

    private final ResidentMemorySource source;
    private final LongSupplier nanoTime;
    private final ScheduledExecutorService executor;
    private final AtomicLong failures = new AtomicLong();
    private final AtomicReference<State> state;

    public static ResidentMemorySampler startDefault(Duration interval) {
        return start(ResidentMemorySources.forCurrentPlatform(), interval);
    }

    static ResidentMemorySampler start(
            Optional<ResidentMemorySource> source, Duration interval) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(interval, "interval");
        if (interval.compareTo(MINIMUM_INTERVAL) < 0) {
            throw new IllegalArgumentException("resident-memory interval is too short");
        }
        if (source.isEmpty()) return new ResidentMemorySampler(null, System::nanoTime, null);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gateway-resident-memory-sampler");
            thread.setDaemon(true);
            return thread;
        });
        ResidentMemorySampler sampler = new ResidentMemorySampler(
                source.orElseThrow(), System::nanoTime, executor);
        sampler.refresh();
        executor.scheduleWithFixedDelay(
                sampler::refresh, interval.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        return sampler;
    }

    ResidentMemorySampler(ResidentMemorySource source, LongSupplier nanoTime) {
        this(source, nanoTime, null);
    }

    private ResidentMemorySampler(
            ResidentMemorySource source,
            LongSupplier nanoTime,
            ScheduledExecutorService executor) {
        this.source = source;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.executor = executor;
        this.state = new AtomicReference<>(new State(false, 0, nanoTime.getAsLong()));
    }

    void refresh() {
        long sampledAt = nanoTime.getAsLong();
        if (source == null) {
            state.set(new State(false, 0, sampledAt));
            return;
        }
        try {
            long bytes = source.readResidentBytes();
            if (bytes < 1) throw new IllegalStateException("resident memory must be positive");
            state.set(new State(true, bytes, sampledAt));
        } catch (Exception ignored) {
            failures.incrementAndGet();
            state.set(new State(false, 0, sampledAt));
        }
    }

    public ResidentMemorySnapshot snapshot() {
        State current = state.get();
        long ageNanos = Math.max(0, nanoTime.getAsLong() - current.sampledAtNanos());
        return new ResidentMemorySnapshot(
                current.available(), current.residentBytes(),
                TimeUnit.NANOSECONDS.toMillis(ageNanos), failures.get());
    }

    @Override
    public void close() {
        if (executor != null) executor.shutdownNow();
    }

    private record State(boolean available, long residentBytes, long sampledAtNanos) {}
}
