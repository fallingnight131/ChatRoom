package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.gateway.transport.WebPushHttpOutcome;
import com.fallingnight.chat.gateway.transport.WebPushHttpTelemetry;
import java.util.Locale;
import java.util.Objects;

/** Renders fixed-name subscription HTTP counters without request labels. */
public final class PrometheusWebPushSubscriptionHttpMetrics {
    private PrometheusWebPushSubscriptionHttpMetrics() { }

    public static String render(WebPushHttpTelemetry telemetry) {
        Objects.requireNonNull(telemetry, "telemetry");
        StringBuilder output = new StringBuilder();
        for (WebPushHttpOutcome outcome : WebPushHttpOutcome.values()) {
            String name = outcome.name().toLowerCase(Locale.ROOT);
            output.append("# TYPE chat_gateway_web_push_subscription_http_")
                    .append(name).append("_total counter\n")
                    .append("chat_gateway_web_push_subscription_http_")
                    .append(name).append("_total ")
                    .append(telemetry.count(outcome)).append('\n');
        }
        return output.toString();
    }
}
