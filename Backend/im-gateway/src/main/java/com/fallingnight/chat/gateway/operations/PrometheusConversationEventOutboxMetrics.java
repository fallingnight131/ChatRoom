package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.messaging.ConversationEventOutboxStatus;
import java.time.Instant;
import java.util.Objects;

/** Fixed-name, identity-free gauges for the durable conversation outbox. */
public final class PrometheusConversationEventOutboxMetrics {
    private PrometheusConversationEventOutboxMetrics() { }

    public static String render(ConversationEventOutboxStatus status, Instant observedAt) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
        StringBuilder output = new StringBuilder();
        gauge(output, "unpublished", status.unpublished());
        gauge(output, "ready", status.ready());
        gauge(output, "leased", status.leased());
        gauge(output, "delayed", status.delayed());
        gauge(output, "retried", status.retried());
        gauge(output, "maximum_attempt_count", status.maximumAttemptCount());
        gauge(output, "oldest_age_seconds", status.oldestAgeSeconds(observedAt));
        return output.toString();
    }

    private static void gauge(StringBuilder output, String name, long value) {
        output.append("# TYPE chat_gateway_outbox_").append(name).append(" gauge\n")
                .append("chat_gateway_outbox_").append(name).append(' ')
                .append(value).append('\n');
    }
}
