package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.gateway.transport.DeviceManagementTelemetrySnapshot;

/** Fixed-label rendering for security-sensitive device operations. */
public final class PrometheusDeviceManagementMetrics {
    private PrometheusDeviceManagementMetrics() { }
    public static String render(DeviceManagementTelemetrySnapshot value) {
        StringBuilder out = new StringBuilder(512)
                .append("# TYPE chat_gateway_device_management_total counter\n");
        counter(out, "listed", value.listed());
        counter(out, "revoked", value.revoked());
        counter(out, "duplicate", value.duplicate());
        counter(out, "disconnected", value.disconnected());
        counter(out, "denied", value.denied());
        counter(out, "invalid", value.invalid());
        counter(out, "saturated", value.saturated());
        counter(out, "failed", value.failed());
        return out.toString();
    }
    private static void counter(StringBuilder out, String outcome, long count) {
        out.append("chat_gateway_device_management_total{outcome=\"")
                .append(outcome).append("\"} ").append(count).append('\n');
    }
}
