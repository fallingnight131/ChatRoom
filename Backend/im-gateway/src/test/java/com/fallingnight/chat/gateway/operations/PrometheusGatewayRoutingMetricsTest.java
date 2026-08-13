package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class PrometheusGatewayRoutingMetricsTest {
    @Test void rendersOnlyFixedIdentityFreeLeaseAndHintMetrics() {
        String value = PrometheusGatewayRoutingMetrics.render(
                new GatewayRouteLeaseTelemetrySnapshot(3, 2, 1, 1, true, 500),
                new GatewayLiveEventConsumerTelemetrySnapshot(4, 1, 8, 5, 1, 1, 1, 2, 200));
        assertTrue(value.contains("chat_gateway_routing_lease_valid 1\n"));
        assertTrue(value.contains("chat_gateway_routing_hint_applied_total 5\n"));
        assertTrue(value.contains("chat_gateway_routing_hint_failed_total 1\n"));
        assertFalse(value.contains("{")); assertFalse(value.contains("conversation"));
        assertFalse(value.contains("gateway_id"));
    }
}
