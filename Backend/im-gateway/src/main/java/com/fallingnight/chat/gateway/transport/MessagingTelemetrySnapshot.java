package com.fallingnight.chat.gateway.transport;

/** Immutable fixed-cardinality messaging counters. */
public record MessagingTelemetrySnapshot(
        long accepted,
        long duplicates,
        long historyPages,
        long directoryPages,
        long reactionChanged,
        long reactionNoOp,
        long reactionDuplicates,
        long editChanged,
        long editNoOp,
        long editDuplicates,
        long forwardAccepted,
        long forwardDuplicates,
        long livePublished,
        long liveSlowConsumerClosed,
        long denied,
        long conflicts,
        long saturated,
        long failed) {}
