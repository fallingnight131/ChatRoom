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
        long forwardRateLimited,
        long accountBlockChanged,
        long accountBlockNoOp,
        long livePublished,
        long liveSlowConsumerClosed,
        long liveSlowConsumerMaximumBytesBeforeWritable,
        long denied,
        long conflicts,
        long saturated,
        long failed) {}
