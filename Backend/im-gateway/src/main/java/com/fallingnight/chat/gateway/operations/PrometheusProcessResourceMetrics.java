package com.fallingnight.chat.gateway.operations;

import java.util.Locale;
import java.util.Objects;

/** Fixed-name Prometheus rendering for portable JVM/process resources. */
public final class PrometheusProcessResourceMetrics {
    private PrometheusProcessResourceMetrics() {}

    public static String render(ProcessResourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "# TYPE chat_gateway_process_cpu_time_available gauge\n"
                + "chat_gateway_process_cpu_time_available "
                + (snapshot.cpuTimeAvailable() ? 1 : 0) + "\n"
                + "# TYPE chat_gateway_process_cpu_seconds_total counter\n"
                + "chat_gateway_process_cpu_seconds_total "
                + seconds(snapshot.processCpuTimeNanos()) + "\n"
                + "# TYPE chat_gateway_jvm_heap_used_bytes gauge\n"
                + "chat_gateway_jvm_heap_used_bytes " + snapshot.heapUsedBytes() + "\n"
                + "# TYPE chat_gateway_jvm_heap_committed_bytes gauge\n"
                + "chat_gateway_jvm_heap_committed_bytes "
                + snapshot.heapCommittedBytes() + "\n"
                + "# TYPE chat_gateway_jvm_heap_maximum_bytes gauge\n"
                + "chat_gateway_jvm_heap_maximum_bytes "
                + snapshot.heapMaximumBytes() + "\n"
                + "# TYPE chat_gateway_process_uptime_seconds gauge\n"
                + "chat_gateway_process_uptime_seconds "
                + String.format(Locale.ROOT, "%.3f", snapshot.uptimeMillis() / 1000.0) + "\n"
                + "# TYPE chat_gateway_process_available_processors gauge\n"
                + "chat_gateway_process_available_processors "
                + snapshot.availableProcessors() + "\n";
    }

    private static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.9f", nanos / 1_000_000_000.0);
    }
}
