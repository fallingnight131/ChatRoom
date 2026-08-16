package com.fallingnight.chat.gateway.transport;

/** Monotonic fixed-cardinality credential issuance outcomes. */
public record WebPushHttpCredentialTelemetrySnapshot(
        long issued, long denied, long saturated, long failed) {
    public WebPushHttpCredentialTelemetrySnapshot {
        if (issued < 0 || denied < 0 || saturated < 0 || failed < 0) {
            throw new IllegalArgumentException("credential counters must not be negative");
        }
    }
}
