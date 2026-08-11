package com.fallingnight.chat.gateway.transport;

/** Immutable fixed-cardinality messaging counters. */
public record MessagingTelemetrySnapshot(
        long accepted,
        long duplicates,
        long historyPages,
        long directoryPages,
        long livePublished,
        long liveSlowConsumerClosed,
        long denied,
        long conflicts,
        long saturated,
        long failed) {}
