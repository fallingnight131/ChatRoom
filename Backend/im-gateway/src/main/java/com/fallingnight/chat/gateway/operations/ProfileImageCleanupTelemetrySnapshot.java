package com.fallingnight.chat.gateway.operations;

public record ProfileImageCleanupTelemetrySnapshot(long runs, long runFailures,
        long claimed, long deleted, long providerFailures, long confirmationFailures,
        int consecutiveFailures, long nextDelaySeconds) { }
