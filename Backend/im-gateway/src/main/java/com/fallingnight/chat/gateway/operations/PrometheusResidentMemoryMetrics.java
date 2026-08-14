package com.fallingnight.chat.gateway.operations;

import java.util.Locale;
import java.util.Objects;

/** Fixed-name Prometheus rendering for cached process resident memory. */
public final class PrometheusResidentMemoryMetrics {
    private PrometheusResidentMemoryMetrics() {}

    public static String render(ResidentMemorySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return "# TYPE chat_gateway_process_resident_memory_available gauge\n"
                + "chat_gateway_process_resident_memory_available "
                + (snapshot.available() ? 1 : 0) + "\n"
                + "# TYPE chat_gateway_process_resident_memory_bytes gauge\n"
                + "chat_gateway_process_resident_memory_bytes "
                + snapshot.residentBytes() + "\n"
                + "# TYPE chat_gateway_process_resident_memory_sample_age_seconds gauge\n"
                + "chat_gateway_process_resident_memory_sample_age_seconds "
                + String.format(Locale.ROOT, "%.3f", snapshot.sampleAgeMillis() / 1000.0)
                + "\n"
                + "# TYPE chat_gateway_process_resident_memory_read_failures_total counter\n"
                + "chat_gateway_process_resident_memory_read_failures_total "
                + snapshot.readFailures() + "\n";
    }
}
