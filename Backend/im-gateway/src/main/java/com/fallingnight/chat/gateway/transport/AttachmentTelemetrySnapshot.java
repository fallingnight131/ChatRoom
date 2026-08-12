package com.fallingnight.chat.gateway.transport;

/** Immutable fixed-cardinality counters for the inactive attachment handler. */
public record AttachmentTelemetrySnapshot(
        long registered,
        long registrationDuplicates,
        long uploadAuthorizations,
        long ready,
        long readyDuplicates,
        long denied,
        long conflicts,
        long invalid,
        long saturated,
        long failed) { }
