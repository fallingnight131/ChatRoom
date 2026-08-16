package com.fallingnight.chat.gateway.operations;

/** Fixed-cardinality, identity-free delivery-loop counters and scheduling gauges. */
public record WebPushDeliveryLoopTelemetrySnapshot(
        long runs,
        long runFailures,
        long workerRejections,
        long claimed,
        long processed,
        long processingFailures,
        long completed,
        long deferred,
        long fenceLost,
        long disabled,
        int consecutiveFailures,
        long nextDelayMillis) { }
