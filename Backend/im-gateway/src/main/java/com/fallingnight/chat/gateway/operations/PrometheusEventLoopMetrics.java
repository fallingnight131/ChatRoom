package com.fallingnight.chat.gateway.operations;

import java.util.Locale;
import java.util.Objects;

/** Fixed-name Prometheus rendering for gateway Netty worker event loops. */
public final class PrometheusEventLoopMetrics {
    private PrometheusEventLoopMetrics() {}

    public static String render(EventLoopSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "# TYPE chat_gateway_event_loop_metrics_available gauge\n"
                + "chat_gateway_event_loop_metrics_available "
                + (snapshot.available() ? 1 : 0) + "\n"
                + "# TYPE chat_gateway_event_loop_workers gauge\n"
                + "chat_gateway_event_loop_workers " + snapshot.workers() + "\n"
                + "# TYPE chat_gateway_event_loop_probe_samples_total counter\n"
                + "chat_gateway_event_loop_probe_samples_total "
                + snapshot.samples() + "\n"
                + "# TYPE chat_gateway_event_loop_latest_max_lag_seconds gauge\n"
                + "chat_gateway_event_loop_latest_max_lag_seconds "
                + seconds(snapshot.latestMaximumLagNanos()) + "\n"
                + "# TYPE chat_gateway_event_loop_max_lag_seconds gauge\n"
                + "chat_gateway_event_loop_max_lag_seconds "
                + seconds(snapshot.maximumLagNanos()) + "\n"
                + "# TYPE chat_gateway_event_loop_pending_tasks gauge\n"
                + "chat_gateway_event_loop_pending_tasks "
                + snapshot.pendingTasks() + "\n";
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.9f", nanos / 1_000_000_000.0);
    }
}
