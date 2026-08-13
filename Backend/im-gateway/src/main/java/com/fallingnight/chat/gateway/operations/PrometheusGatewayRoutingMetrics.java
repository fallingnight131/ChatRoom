package com.fallingnight.chat.gateway.operations;

import java.util.Objects;

/** Fixed-name, identity-free gateway route lease and hint-consumer metrics. */
public final class PrometheusGatewayRoutingMetrics {
    private PrometheusGatewayRoutingMetrics() { }

    public static String render(GatewayRouteLeaseTelemetrySnapshot lease,
            GatewayLiveEventConsumerTelemetrySnapshot consumer) {
        Objects.requireNonNull(lease, "lease"); Objects.requireNonNull(consumer, "consumer");
        StringBuilder output = new StringBuilder();
        counter(output, "lease_attempts", lease.attempts());
        counter(output, "lease_renewed", lease.renewed());
        counter(output, "lease_failed", lease.failed());
        gauge(output, "lease_valid", lease.leaseValid() ? 1 : 0);
        gauge(output, "lease_consecutive_failures", lease.consecutiveFailures());
        gauge(output, "lease_next_delay_milliseconds", lease.nextDelayMillis());
        counter(output, "hint_runs", consumer.runs());
        counter(output, "hint_run_failures", consumer.runFailures());
        counter(output, "hint_read", consumer.read());
        counter(output, "hint_applied", consumer.applied());
        counter(output, "hint_duplicates", consumer.duplicates());
        counter(output, "hint_not_subscribed", consumer.notSubscribed());
        counter(output, "hint_failed", consumer.failed());
        gauge(output, "hint_consecutive_failures", consumer.consecutiveFailures());
        gauge(output, "hint_next_delay_milliseconds", consumer.nextDelayMillis());
        return output.toString();
    }
    private static void counter(StringBuilder output, String name, long value) {
        output.append("# TYPE chat_gateway_routing_").append(name).append("_total counter\n")
                .append("chat_gateway_routing_").append(name).append("_total ")
                .append(value).append('\n');
    }
    private static void gauge(StringBuilder output, String name, long value) {
        output.append("# TYPE chat_gateway_routing_").append(name).append(" gauge\n")
                .append("chat_gateway_routing_").append(name).append(' ')
                .append(value).append('\n');
    }
}
