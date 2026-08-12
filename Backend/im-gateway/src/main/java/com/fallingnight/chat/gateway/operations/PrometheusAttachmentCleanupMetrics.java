package com.fallingnight.chat.gateway.operations;

/** Renders fixed attachment-cleanup outcomes and scheduler gauges. */
public final class PrometheusAttachmentCleanupMetrics {
    private PrometheusAttachmentCleanupMetrics() {}

    public static String render(AttachmentCleanupTelemetrySnapshot snapshot) {
        StringBuilder output = new StringBuilder(640)
                .append("# TYPE chat_gateway_attachment_cleanup_total counter\n");
        counter(output, "run", snapshot.runs());
        counter(output, "run_failure", snapshot.runFailures());
        counter(output, "revoked", snapshot.revoked());
        counter(output, "attempted", snapshot.attempted());
        counter(output, "deleted", snapshot.deleted());
        counter(output, "provider_failure", snapshot.providerFailures());
        counter(output, "confirmation_failure", snapshot.confirmationFailures());
        output.append("# TYPE chat_gateway_attachment_cleanup_consecutive_failures gauge\n")
                .append("chat_gateway_attachment_cleanup_consecutive_failures ")
                .append(snapshot.consecutiveFailures()).append('\n')
                .append("# TYPE chat_gateway_attachment_cleanup_next_delay_seconds gauge\n")
                .append("chat_gateway_attachment_cleanup_next_delay_seconds ")
                .append(snapshot.nextDelaySeconds()).append('\n');
        return output.toString();
    }

    private static void counter(StringBuilder output, String outcome, long value) {
        output.append("chat_gateway_attachment_cleanup_total{outcome=\"")
                .append(outcome).append("\"} ").append(value).append('\n');
    }
}
