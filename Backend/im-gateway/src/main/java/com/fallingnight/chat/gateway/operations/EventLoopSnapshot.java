package com.fallingnight.chat.gateway.operations;

/** Fixed-cardinality observation of the gateway Netty worker event loops. */
public record EventLoopSnapshot(
        boolean available,
        int workers,
        long samples,
        long latestMaximumLagNanos,
        long maximumLagNanos,
        long pendingTasks) {

    public EventLoopSnapshot {
        if (workers < 0 || samples < 0 || latestMaximumLagNanos < 0
                || maximumLagNanos < 0 || pendingTasks < 0) {
            throw new IllegalArgumentException("event-loop gauges cannot be negative");
        }
        if (latestMaximumLagNanos > maximumLagNanos) {
            throw new IllegalArgumentException("latest event-loop lag exceeds maximum");
        }
        if (available && workers == 0) {
            throw new IllegalArgumentException("available event-loop snapshot has no workers");
        }
        if (!available && (workers != 0 || samples != 0 || latestMaximumLagNanos != 0
                || maximumLagNanos != 0 || pendingTasks != 0)) {
            throw new IllegalArgumentException("unavailable event-loop snapshot has values");
        }
    }

    public static EventLoopSnapshot unavailable() {
        return new EventLoopSnapshot(false, 0, 0, 0, 0, 0);
    }
}
