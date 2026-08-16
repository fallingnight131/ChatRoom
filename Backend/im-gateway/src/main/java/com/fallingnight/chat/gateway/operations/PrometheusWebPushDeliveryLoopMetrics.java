package com.fallingnight.chat.gateway.operations;

import java.util.Objects;

/** Label-free Prometheus rendering for the detached Web Push delivery loop. */
public final class PrometheusWebPushDeliveryLoopMetrics {
    private PrometheusWebPushDeliveryLoopMetrics() { }

    public static String render(WebPushDeliveryLoopTelemetrySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        StringBuilder output = new StringBuilder();
        counter(output, "runs", snapshot.runs());
        counter(output, "run_failures", snapshot.runFailures());
        counter(output, "worker_rejections", snapshot.workerRejections());
        counter(output, "claimed", snapshot.claimed());
        counter(output, "processed", snapshot.processed());
        counter(output, "processing_failures", snapshot.processingFailures());
        counter(output, "completed", snapshot.completed());
        counter(output, "deferred", snapshot.deferred());
        counter(output, "fence_lost", snapshot.fenceLost());
        counter(output, "disabled", snapshot.disabled());
        gauge(output, "consecutive_failures", snapshot.consecutiveFailures());
        gauge(output, "next_delay_millis", snapshot.nextDelayMillis());
        return output.toString();
    }

    private static void counter(StringBuilder output, String name, long value) {
        metric(output, name + "_total", "counter", value);
    }

    private static void gauge(StringBuilder output, String name, long value) {
        metric(output, name, "gauge", value);
    }

    private static void metric(
            StringBuilder output, String name, String type, long value) {
        String metric = "chat_gateway_web_push_delivery_loop_" + name;
        output.append("# TYPE ").append(metric).append(' ').append(type).append('\n')
                .append(metric).append(' ').append(value).append('\n');
    }
}
