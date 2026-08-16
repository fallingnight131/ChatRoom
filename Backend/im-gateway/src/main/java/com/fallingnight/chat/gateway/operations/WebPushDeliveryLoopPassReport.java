package com.fallingnight.chat.gateway.operations;

/** Identity-free result of one bounded delivery-loop pass. */
public record WebPushDeliveryLoopPassReport(
        int claimed,
        int processed,
        int processingFailures,
        int completed,
        int deferred,
        int fenceLost,
        int disabled) {
    public WebPushDeliveryLoopPassReport {
        if (claimed < 0 || processed < 0 || processingFailures < 0
                || completed < 0 || deferred < 0 || fenceLost < 0 || disabled < 0
                || claimed != processed + processingFailures
                || processed != completed + deferred + fenceLost + disabled) {
            throw new IllegalArgumentException("invalid Web Push delivery-loop report");
        }
    }
}
