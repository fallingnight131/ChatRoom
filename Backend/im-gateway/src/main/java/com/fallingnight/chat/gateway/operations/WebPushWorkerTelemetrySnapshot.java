package com.fallingnight.chat.gateway.operations;

/** Fixed-cardinality, identity-free Web Push worker counters. */
public record WebPushWorkerTelemetrySnapshot(
        long recipientSaturated,
        long delivered,
        long invalidSubscriptions,
        long transientFailures,
        long authenticationFailures,
        long ineligible,
        long deferred,
        long completedDelivered,
        long completedExpired,
        long completedIneligible,
        long completedInvalidSubscription,
        long fenceLost) { }
