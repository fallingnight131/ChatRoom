package com.fallingnight.chat.gateway.operations;

/** Cached process resident-memory observation with fixed availability semantics. */
public record ResidentMemorySnapshot(
        boolean available,
        long residentBytes,
        long sampleAgeMillis,
        long readFailures) {

    public ResidentMemorySnapshot {
        if (residentBytes < 0 || sampleAgeMillis < 0 || readFailures < 0) {
            throw new IllegalArgumentException("resident-memory gauges are invalid");
        }
        if (available && residentBytes < 1) {
            throw new IllegalArgumentException("available resident memory must be positive");
        }
        if (!available && residentBytes != 0) {
            throw new IllegalArgumentException("unavailable resident memory must be zero");
        }
    }
}
