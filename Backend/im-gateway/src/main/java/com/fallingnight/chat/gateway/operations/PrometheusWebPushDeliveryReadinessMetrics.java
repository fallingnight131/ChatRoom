package com.fallingnight.chat.gateway.operations;

import java.util.Objects;

/** Label-free readiness and one-hot reason metrics for the optional push component. */
public final class PrometheusWebPushDeliveryReadinessMetrics {
    private PrometheusWebPushDeliveryReadinessMetrics() { }

    public static String render(WebPushDeliveryReadiness readiness) {
        Objects.requireNonNull(readiness, "readiness");
        StringBuilder output = new StringBuilder();
        gauge(output, "ready", readiness.ready() ? 1 : 0);
        for (WebPushDeliveryReadiness.Reason reason
                : WebPushDeliveryReadiness.Reason.values()) {
            gauge(output, "reason_" + reason.name().toLowerCase(java.util.Locale.ROOT),
                    readiness.reason() == reason ? 1 : 0);
        }
        return output.toString();
    }

    private static void gauge(StringBuilder output, String name, long value) {
        String metric = "chat_gateway_web_push_delivery_" + name;
        output.append("# TYPE ").append(metric).append(" gauge\n")
                .append(metric).append(' ').append(value).append('\n');
    }
}
