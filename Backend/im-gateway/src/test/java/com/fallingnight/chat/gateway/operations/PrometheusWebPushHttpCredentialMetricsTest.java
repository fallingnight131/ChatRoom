package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.gateway.transport.WebPushHttpCredentialTelemetry;
import org.junit.jupiter.api.Test;

class PrometheusWebPushHttpCredentialMetricsTest {
    @Test
    void rendersOnlyFixedIdentityFreeCounters() {
        WebPushHttpCredentialTelemetry telemetry = new WebPushHttpCredentialTelemetry();
        telemetry.issued(); telemetry.denied(); telemetry.saturated(); telemetry.failed();
        String metrics = PrometheusWebPushHttpCredentialMetrics.render(telemetry.snapshot());
        assertTrue(metrics.contains("chat_gateway_web_push_http_credentials_issued_total 1\n"));
        assertTrue(metrics.contains("chat_gateway_web_push_http_credentials_denied_total 1\n"));
        assertTrue(metrics.contains("chat_gateway_web_push_http_credentials_saturated_total 1\n"));
        assertTrue(metrics.contains("chat_gateway_web_push_http_credentials_failed_total 1\n"));
        assertFalse(metrics.contains("{"));
    }
}
