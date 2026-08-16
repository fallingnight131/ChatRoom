package com.fallingnight.chat.gateway.operations;

import java.util.Objects;

/** Fixed metric names and no labels, identifiers, endpoints, or provider bodies. */
public final class PrometheusWebPushWorkerMetrics {
    private PrometheusWebPushWorkerMetrics() { }

    public static String render(WebPushWorkerTelemetrySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder output = new StringBuilder();
        counter(output, "recipient_saturated", snapshot.recipientSaturated());
        counter(output, "delivered", snapshot.delivered());
        counter(output, "invalid_subscriptions", snapshot.invalidSubscriptions());
        counter(output, "transient_failures", snapshot.transientFailures());
        counter(output, "authentication_failures", snapshot.authenticationFailures());
        counter(output, "ineligible", snapshot.ineligible());
        counter(output, "deferred", snapshot.deferred());
        counter(output, "completed_delivered", snapshot.completedDelivered());
        counter(output, "completed_expired", snapshot.completedExpired());
        counter(output, "completed_ineligible", snapshot.completedIneligible());
        counter(output, "completed_invalid_subscription",
                snapshot.completedInvalidSubscription());
        counter(output, "fence_lost", snapshot.fenceLost());
        return output.toString();
    }

    private static void counter(StringBuilder output, String name, long value) {
        output.append("# TYPE chat_gateway_web_push_worker_").append(name)
                .append("_total counter\n")
                .append("chat_gateway_web_push_worker_").append(name).append("_total ")
                .append(value).append('\n');
    }
}
