package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.notification.WebPushOutboxStatus;
import com.fallingnight.chat.application.notification.WebPushOutboxStatusPort;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Fail-closed readiness probe that avoids touching PostgreSQL while the worker is stopped. */
public final class WebPushDeliveryReadinessProbe {
    private final BooleanSupplier running;
    private final WebPushOutboxStatusPort status;
    private final Supplier<WebPushDeliveryLoopTelemetrySnapshot> telemetry;
    private final WebPushDeliveryReadinessPolicy policy;
    private final Clock clock;

    public WebPushDeliveryReadinessProbe(
            BooleanSupplier running,
            WebPushOutboxStatusPort status,
            Supplier<WebPushDeliveryLoopTelemetrySnapshot> telemetry,
            WebPushDeliveryReadinessPolicy policy,
            Clock clock) {
        this.running = Objects.requireNonNull(running, "running");
        this.status = Objects.requireNonNull(status, "status");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public WebPushDeliveryReadiness check() {
        try {
            if (!running.getAsBoolean()) {
                return WebPushDeliveryReadiness.unready(
                        WebPushDeliveryReadiness.Reason.STOPPED);
            }
            Instant observedAt = clock.instant();
            WebPushOutboxStatus snapshot = Objects.requireNonNull(
                    status.readStatus(observedAt), "statusSnapshot");
            WebPushDeliveryLoopTelemetrySnapshot loop = Objects.requireNonNull(
                    telemetry.get(), "telemetrySnapshot");
            return policy.evaluate(snapshot, loop, observedAt);
        } catch (RuntimeException exception) {
            return WebPushDeliveryReadiness.unready(
                    WebPushDeliveryReadiness.Reason.STATUS_UNAVAILABLE);
        }
    }
}
