package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.routing.GatewayLiveEventConsumerReport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Identity-free counters for Redis hint consumption and authoritative repair. */
public final class GatewayLiveEventConsumerTelemetry {
    private final LongAdder runs = new LongAdder(), runFailures = new LongAdder();
    private final LongAdder read = new LongAdder(), applied = new LongAdder();
    private final LongAdder duplicates = new LongAdder(), notSubscribed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong nextDelayMillis = new AtomicLong();

    public void completed(GatewayLiveEventConsumerReport report,
            int failures, Duration nextDelay) {
        Objects.requireNonNull(report, "report"); runs.increment(); read.add(report.read());
        applied.add(report.applied()); duplicates.add(report.duplicates());
        notSubscribed.add(report.notSubscribed()); failed.add(report.failed());
        scheduled(failures, nextDelay);
    }
    public void failed(int failures, Duration nextDelay) {
        runs.increment(); runFailures.increment(); scheduled(failures, nextDelay);
    }
    public GatewayLiveEventConsumerTelemetrySnapshot snapshot() {
        return new GatewayLiveEventConsumerTelemetrySnapshot(runs.sum(), runFailures.sum(),
                read.sum(), applied.sum(), duplicates.sum(), notSubscribed.sum(), failed.sum(),
                consecutiveFailures.get(), nextDelayMillis.get());
    }
    private void scheduled(int failures, Duration delay) {
        if (failures < 0) throw new IllegalArgumentException("failures must not be negative");
        consecutiveFailures.set(failures);
        nextDelayMillis.set(Objects.requireNonNull(delay, "delay").toMillis());
    }
}
