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
