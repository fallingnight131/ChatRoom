package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.notification.WebPushOutboxStatusPort;
import com.fallingnight.chat.gateway.operations.PrometheusWebPushDeliveryLoopMetrics;
import com.fallingnight.chat.gateway.operations.PrometheusWebPushDeliveryReadinessMetrics;
import com.fallingnight.chat.gateway.operations.PrometheusWebPushOutboxMetrics;
import com.fallingnight.chat.gateway.operations.PrometheusWebPushWorkerMetrics;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryLoopTelemetry;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryReadiness;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryReadinessProbe;
import com.fallingnight.chat.gateway.operations.WebPushDeliveryRuntime;
import com.fallingnight.chat.gateway.operations.WebPushWorkerTelemetry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Owns a composed optional delivery runtime and its separate VAPID custody. */
public final class WebPushDeliveryComponents implements AutoCloseable {
    private final WebPushDeliveryRuntime runtime;
    private final AutoCloseable vapidCustody;
    private final WebPushDeliveryReadinessProbe readiness;
    private final WebPushOutboxStatusPort outboxStatus;
    private final WebPushDeliveryLoopTelemetry loopTelemetry;
    private final WebPushWorkerTelemetry workerTelemetry;
    private final Clock clock;
    private boolean closed;

    WebPushDeliveryComponents(
            WebPushDeliveryRuntime runtime,
            AutoCloseable vapidCustody,
            WebPushDeliveryReadinessProbe readiness,
            WebPushOutboxStatusPort outboxStatus,
            WebPushDeliveryLoopTelemetry loopTelemetry,
            WebPushWorkerTelemetry workerTelemetry,
            Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.vapidCustody = Objects.requireNonNull(vapidCustody, "vapidCustody");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.outboxStatus = Objects.requireNonNull(outboxStatus, "outboxStatus");
        this.loopTelemetry = Objects.requireNonNull(loopTelemetry, "loopTelemetry");
        this.workerTelemetry = Objects.requireNonNull(workerTelemetry, "workerTelemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("Web Push delivery components are closed");
        runtime.start();
    }

    public WebPushDeliveryReadiness readiness() {
        return readiness.check();
    }

    public String metrics() {
        Instant observedAt = clock.instant();
        WebPushDeliveryReadiness health = readiness();
        StringBuilder output = new StringBuilder()
                .append(PrometheusWebPushDeliveryReadinessMetrics.render(health))
                .append(PrometheusWebPushDeliveryLoopMetrics.render(loopTelemetry.snapshot()))
                .append(PrometheusWebPushWorkerMetrics.render(workerTelemetry.snapshot()));
        if (health.reason() == WebPushDeliveryReadiness.Reason.STOPPED
                || health.reason() == WebPushDeliveryReadiness.Reason.STATUS_UNAVAILABLE) {
            return output.toString();
        }
        try {
            output.append(PrometheusWebPushOutboxMetrics.render(
                    outboxStatus.readStatus(observedAt), observedAt));
        } catch (RuntimeException exception) {
            // Readiness already exposes a fixed unavailable reason; never reflect storage details.
        }
        return output.toString();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = close(runtime, null);
        failure = close(vapidCustody, failure);
        if (failure != null) throw failure;
    }

    private static RuntimeException close(
            AutoCloseable resource, RuntimeException previous) {
        try {
            resource.close();
            return previous;
        } catch (Exception exception) {
            RuntimeException failure = exception instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException("Web Push delivery cleanup failed", exception);
            if (previous == null) return failure;
            previous.addSuppressed(failure);
            return previous;
        }
    }
}
