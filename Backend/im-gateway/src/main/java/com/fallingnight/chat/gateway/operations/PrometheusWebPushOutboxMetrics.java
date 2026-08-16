package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.notification.WebPushOutboxStatus;
import java.time.Instant;
import java.util.Objects;

/** Fixed-name, identity-free gauges for the durable Web Push outbox. */
public final class PrometheusWebPushOutboxMetrics {
    private PrometheusWebPushOutboxMetrics() { }

    public static String render(WebPushOutboxStatus status, Instant observedAt) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        StringBuilder output = new StringBuilder();
        gauge(output, "pending", status.pending());
        gauge(output, "ready", status.ready());
        gauge(output, "leased", status.leased());
        gauge(output, "delayed", status.delayed());
        gauge(output, "expired", status.expired());
        gauge(output, "retried", status.retried());
        gauge(output, "maximum_attempt_count", status.maximumAttemptCount());
        gauge(output, "oldest_age_seconds", status.oldestAgeSeconds(observedAt));
        return output.toString();
    }

    private static void gauge(StringBuilder output, String name, long value) {
        String metric = "chat_gateway_web_push_outbox_" + name;
        output.append("# TYPE ").append(metric).append(" gauge\n")
                .append(metric).append(' ').append(value).append('\n');
    }
}
