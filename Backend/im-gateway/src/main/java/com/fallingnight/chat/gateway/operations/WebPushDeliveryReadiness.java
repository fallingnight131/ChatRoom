package com.fallingnight.chat.gateway.operations;

import java.util.Objects;

/** Fixed-cardinality readiness result for the optional Web Push delivery component. */
public record WebPushDeliveryReadiness(boolean ready, Reason reason) {
    public WebPushDeliveryReadiness {
        Objects.requireNonNull(reason, "reason");
        if (ready != (reason == Reason.HEALTHY)) {
            throw new IllegalArgumentException("Web Push readiness reason is inconsistent");
        }
    }

    public static WebPushDeliveryReadiness healthy() {
        return new WebPushDeliveryReadiness(true, Reason.HEALTHY);
    }

    public static WebPushDeliveryReadiness unready(Reason reason) {
        return new WebPushDeliveryReadiness(false, reason);
    }

    public enum Reason {
        HEALTHY,
        STOPPED,
        STATUS_UNAVAILABLE,
        CONSECUTIVE_FAILURES,
        EXPIRED_BACKLOG,
        BACKLOG_COUNT,
        BACKLOG_AGE
    }
}
