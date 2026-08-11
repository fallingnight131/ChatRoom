package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.gateway.transport.AuthenticationLimitDimension;
import com.fallingnight.chat.gateway.transport.AuthenticationOutcome;
import com.fallingnight.chat.gateway.transport.AuthenticationTelemetry;
import org.junit.jupiter.api.Test;

class PrometheusAuthenticationMetricsTest {
    @Test
    void rendersBoundedLabelsAndCumulativeDurationBuckets() {
        AuthenticationTelemetry telemetry = new AuthenticationTelemetry();
        telemetry.accepted(true);
        telemetry.rejected();
        telemetry.failed();
        telemetry.saturated();
        telemetry.admissionDenied(AuthenticationLimitDimension.DIRECT_PEER);
        telemetry.completed(AuthenticationOutcome.ACCEPTED, true, 2_000_000L);
        telemetry.completed(AuthenticationOutcome.REJECTED, false, 7_000_000L);

        String rendered = PrometheusAuthenticationMetrics.render(telemetry.snapshot());

        assertTrue(rendered.contains(
                "chat_gateway_authentication_total{outcome=\"accepted\"} 1"));
        assertTrue(rendered.contains(
                "chat_gateway_authentication_admission_denied_total"
                        + "{dimension=\"direct_peer\"} 1"));
        assertTrue(rendered.contains(
                "chat_gateway_authentication_execution_duration_seconds_bucket"
                        + "{le=\"0.005\"} 1"));
        assertTrue(rendered.contains(
                "chat_gateway_authentication_execution_duration_seconds_bucket"
                        + "{le=\"0.01\"} 2"));
        assertTrue(rendered.contains(
                "chat_gateway_authentication_execution_duration_seconds_bucket"
                        + "{le=\"+Inf\"} 2"));
        assertTrue(rendered.contains(
                "chat_gateway_authentication_execution_duration_seconds_count 2"));
        assertFalse(rendered.contains("alice"));
        assertFalse(rendered.contains("192.0.2.1"));
    }
}
