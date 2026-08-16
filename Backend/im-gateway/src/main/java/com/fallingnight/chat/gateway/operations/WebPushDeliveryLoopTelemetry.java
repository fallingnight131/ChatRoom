package com.fallingnight.chat.gateway.operations;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fixed-cardinality delivery-loop telemetry without account, message, or claim IDs. */
public final class WebPushDeliveryLoopTelemetry {
    private final LongAdder runs = new LongAdder();
    private final LongAdder runFailures = new LongAdder();
    private final LongAdder workerRejections = new LongAdder();
    private final LongAdder claimed = new LongAdder();
    private final LongAdder processed = new LongAdder();
    private final LongAdder processingFailures = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder deferred = new LongAdder();
    private final LongAdder fenceLost = new LongAdder();
    private final LongAdder disabled = new LongAdder();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong nextDelayMillis = new AtomicLong();

    public void completed(
            WebPushDeliveryLoopPassReport report, int failures, Duration nextDelay) {
        Objects.requireNonNull(report, "report");
        runs.increment();
        claimed.add(report.claimed());
        processed.add(report.processed());
        processingFailures.add(report.processingFailures());
        completed.add(report.completed());
        deferred.add(report.deferred());
        fenceLost.add(report.fenceLost());
        disabled.add(report.disabled());
        scheduled(failures, nextDelay);
    }

    public void failed(int failures, Duration nextDelay) {
        runs.increment();
        runFailures.increment();
        scheduled(failures, nextDelay);
    }

    public void workerRejected(int failures, Duration nextDelay) {
        workerRejections.increment();
        failed(failures, nextDelay);
    }

    public WebPushDeliveryLoopTelemetrySnapshot snapshot() {
        return new WebPushDeliveryLoopTelemetrySnapshot(
                runs.sum(), runFailures.sum(), workerRejections.sum(), claimed.sum(),
                processed.sum(), processingFailures.sum(), completed.sum(), deferred.sum(),
                fenceLost.sum(), disabled.sum(), consecutiveFailures.get(),
                nextDelayMillis.get());
    }

    private void scheduled(int failures, Duration delay) {
        if (failures < 0) {
            throw new IllegalArgumentException("failures must not be negative");
        }
        Objects.requireNonNull(delay, "delay");
        consecutiveFailures.set(failures);
        nextDelayMillis.set(delay.toMillis());
    }
}
