package com.fallingnight.chat.gateway.operations;

/** Fixed-cardinality cleanup counters and scheduler gauges. */
public record AttachmentCleanupTelemetrySnapshot(
        long runs,
        long runFailures,
        long revoked,
        long attempted,
        long deleted,
        long providerFailures,
        long confirmationFailures,
        int consecutiveFailures,
        long nextDelaySeconds) {}
