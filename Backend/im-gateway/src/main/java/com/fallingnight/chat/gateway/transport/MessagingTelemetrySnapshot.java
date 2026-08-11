package com.fallingnight.chat.gateway.transport;

/** Immutable fixed-cardinality messaging counters. */
public record MessagingTelemetrySnapshot(
        long accepted,
        long duplicates,
        long historyPages,
        long denied,
        long conflicts,
        long saturated,
        long failed) {}
