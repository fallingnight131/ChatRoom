package com.fallingnight.chat.gateway.transport;

import java.util.Map;

/** Immutable non-secret authentication metrics snapshot. */
public record AuthenticationTelemetrySnapshot(
        long accepted,
        long rejected,
        long failed,
        long saturated,
        long credentialUpgradePending,
        Map<AuthenticationLimitDimension, Long> admissionDenials,
        Map<String, Long> executionDurationBuckets,
        long executionDurationCount,
        long executionDurationTotalNanos,
        long executionDurationMaxNanos) {
    public AuthenticationTelemetrySnapshot {
        admissionDenials = Map.copyOf(admissionDenials);
        executionDurationBuckets = Map.copyOf(executionDurationBuckets);
    }
}
