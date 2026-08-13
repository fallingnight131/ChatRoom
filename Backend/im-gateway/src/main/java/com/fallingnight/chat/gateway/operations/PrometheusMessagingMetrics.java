package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.gateway.transport.MessagingTelemetrySnapshot;

/** Renders fixed-label message outcomes and bounded worker gauges. */
public final class PrometheusMessagingMetrics {
    private PrometheusMessagingMetrics() {}

    public static String render(
            MessagingTelemetrySnapshot snapshot, int activeWorkers, int queuedWork) {
        StringBuilder output = new StringBuilder(768)
                .append("# TYPE chat_gateway_messaging_total counter\n");
        counter(output, "accepted", snapshot.accepted());
        counter(output, "duplicate", snapshot.duplicates());
        counter(output, "history_page", snapshot.historyPages());
        counter(output, "directory_page", snapshot.directoryPages());
        counter(output, "reaction_changed", snapshot.reactionChanged());
        counter(output, "reaction_noop", snapshot.reactionNoOp());
        counter(output, "reaction_duplicate", snapshot.reactionDuplicates());
        counter(output, "edit_changed", snapshot.editChanged());
        counter(output, "edit_noop", snapshot.editNoOp());
        counter(output, "edit_duplicate", snapshot.editDuplicates());
        counter(output, "forward_accepted", snapshot.forwardAccepted());
        counter(output, "forward_duplicate", snapshot.forwardDuplicates());
        counter(output, "forward_rate_limited", snapshot.forwardRateLimited());
        counter(output, "live_published", snapshot.livePublished());
        counter(output, "live_slow_consumer_closed", snapshot.liveSlowConsumerClosed());
        counter(output, "denied", snapshot.denied());
        counter(output, "conflict", snapshot.conflicts());
        counter(output, "saturated", snapshot.saturated());
        counter(output, "failed", snapshot.failed());
        output.append("# TYPE chat_gateway_messaging_workers_active gauge\n")
                .append("chat_gateway_messaging_workers_active ").append(activeWorkers).append('\n')
                .append("# TYPE chat_gateway_messaging_queue_size gauge\n")
                .append("chat_gateway_messaging_queue_size ").append(queuedWork).append('\n');
        return output.toString();
    }

    private static void counter(StringBuilder output, String outcome, long value) {
        output.append("chat_gateway_messaging_total{outcome=\"")
                .append(outcome).append("\"} ").append(value).append('\n');
    }
}
