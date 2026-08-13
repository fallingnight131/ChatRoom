package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.gateway.transport.AuthenticationLimitDimension;
import com.fallingnight.chat.gateway.transport.AuthenticationTelemetrySnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Renders only fixed-name, bounded-label authentication metrics. */
public final class PrometheusAuthenticationMetrics {
    private PrometheusAuthenticationMetrics() {}

    public static String render(AuthenticationTelemetrySnapshot snapshot) {
        return render(snapshot, 0, 0);
    }

    public static String render(
            AuthenticationTelemetrySnapshot snapshot, int activeWorkers, int queuedWork) {
        if (activeWorkers < 0 || queuedWork < 0) {
            throw new IllegalArgumentException("authentication worker gauges cannot be negative");
        }
        StringBuilder output = new StringBuilder(2048);
        output.append("# TYPE chat_gateway_authentication_total counter\n");
        counter(output, "accepted", snapshot.accepted());
        counter(output, "rejected", snapshot.rejected());
        counter(output, "failed", snapshot.failed());
        counter(output, "saturated", snapshot.saturated());
        output.append("# TYPE chat_gateway_authentication_upgrade_pending_total counter\n")
                .append("chat_gateway_authentication_upgrade_pending_total ")
                .append(snapshot.credentialUpgradePending()).append('\n');
        output.append("# TYPE chat_gateway_authentication_admission_denied_total counter\n");
        for (AuthenticationLimitDimension dimension : AuthenticationLimitDimension.values()) {
            output.append("chat_gateway_authentication_admission_denied_total{dimension=\"")
                    .append(dimension.name().toLowerCase(Locale.ROOT))
                    .append("\"} ")
                    .append(snapshot.admissionDenials().getOrDefault(dimension, 0L))
                    .append('\n');
        }

        output.append("# TYPE chat_gateway_authentication_execution_duration_seconds histogram\n");
        long cumulative = 0;
        for (DurationBucket bucket : durationBuckets(snapshot)) {
            cumulative += bucket.count();
            output.append("chat_gateway_authentication_execution_duration_seconds_bucket{le=\"")
                    .append(bucket.boundarySeconds())
                    .append("\"} ")
                    .append(cumulative)
                    .append('\n');
        }
        output.append("chat_gateway_authentication_execution_duration_seconds_count ")
                .append(snapshot.executionDurationCount()).append('\n');
        output.append("chat_gateway_authentication_execution_duration_seconds_sum ")
                .append(seconds(snapshot.executionDurationTotalNanos())).append('\n');
        output.append("# TYPE chat_gateway_authentication_execution_duration_max_seconds gauge\n")
                .append("chat_gateway_authentication_execution_duration_max_seconds ")
                .append(seconds(snapshot.executionDurationMaxNanos())).append('\n')
                .append("# TYPE chat_gateway_authentication_workers_active gauge\n")
                .append("chat_gateway_authentication_workers_active ")
                .append(activeWorkers).append('\n')
                .append("# TYPE chat_gateway_authentication_queue_size gauge\n")
                .append("chat_gateway_authentication_queue_size ")
                .append(queuedWork).append('\n');
        return output.toString();
    }

    private static void counter(StringBuilder output, String outcome, long value) {
        output.append("chat_gateway_authentication_total{outcome=\"")
                .append(outcome).append("\"} ").append(value).append('\n');
    }

    private static List<DurationBucket> durationBuckets(
            AuthenticationTelemetrySnapshot snapshot) {
        List<DurationBucket> finite = new ArrayList<>();
        long infinite = 0;
        for (var entry : snapshot.executionDurationBuckets().entrySet()) {
            if ("+Inf".equals(entry.getKey())) {
                infinite = entry.getValue();
            } else {
                finite.add(new DurationBucket(
                        Long.parseLong(entry.getKey()),
                        seconds(Long.parseLong(entry.getKey())),
                        entry.getValue()));
            }
        }
        finite.sort(Comparator.comparingLong(DurationBucket::boundaryNanos));
        finite.add(new DurationBucket(Long.MAX_VALUE, "+Inf", infinite));
        return List.copyOf(finite);
    }

    private static String seconds(long nanos) {
        return Double.toString(nanos / 1_000_000_000.0d);
    }

    private record DurationBucket(long boundaryNanos, String boundarySeconds, long count) {}
}
