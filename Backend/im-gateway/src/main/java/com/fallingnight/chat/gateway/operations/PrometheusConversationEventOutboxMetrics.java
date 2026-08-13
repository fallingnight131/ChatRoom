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

    public static String renderRelay(ConversationEventRelayTelemetrySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder output = new StringBuilder();
        counter(output, "runs", snapshot.runs());
        counter(output, "run_failures", snapshot.runFailures());
        counter(output, "claimed", snapshot.claimed());
        counter(output, "published", snapshot.published());
        counter(output, "deferred", snapshot.deferred());
        counter(output, "ownership_lost", snapshot.ownershipLost());
        counter(output, "publisher_failures", snapshot.publisherFailures());
        gauge(output, "relay_consecutive_failures", snapshot.consecutiveFailures());
        gauge(output, "relay_next_delay_milliseconds", snapshot.nextDelayMillis());
        return output.toString();
    }

    private static void counter(StringBuilder output, String name, long value) {
        output.append("# TYPE chat_gateway_outbox_relay_").append(name)
                .append("_total counter\n")
                .append("chat_gateway_outbox_relay_").append(name).append("_total ")
                .append(value).append('\n');
    }

    private static void gauge(StringBuilder output, String name, long value) {
        output.append("# TYPE chat_gateway_outbox_").append(name).append(" gauge\n")
                .append("chat_gateway_outbox_").append(name).append(' ')
                .append(value).append('\n');
    }
}
