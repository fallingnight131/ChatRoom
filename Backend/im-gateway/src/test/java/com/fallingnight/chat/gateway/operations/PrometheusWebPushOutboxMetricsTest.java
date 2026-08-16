package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushOutboxStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PrometheusWebPushOutboxMetricsTest {
    @Test
    void rendersOnlyFixedIdentityFreeGauges() {
        Instant observedAt = Instant.parse("2026-08-17T00:01:00Z");
        String rendered = PrometheusWebPushOutboxMetrics.render(
                new WebPushOutboxStatus(10, 3, 2, 4, 1, 5, 7,
                        Optional.of(observedAt.minusSeconds(60))), observedAt);

        assertTrue(rendered.contains("chat_gateway_web_push_outbox_pending 10\n"));
        assertTrue(rendered.contains("chat_gateway_web_push_outbox_ready 3\n"));
        assertTrue(rendered.contains("chat_gateway_web_push_outbox_oldest_age_seconds 60\n"));
        assertFalse(rendered.contains("{"));
        assertFalse(rendered.contains("account"));
        assertFalse(rendered.contains("message"));
        assertFalse(rendered.contains("conversation"));
    }
}
