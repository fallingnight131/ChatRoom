package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.messaging.ConversationEventOutboxStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class PrometheusConversationEventOutboxMetricsTest {
    @Test
    void rendersOnlyFixedIdentityFreeGauges() {
        Instant observed = Instant.parse("2030-01-01T00:01:00Z");
        String rendered = PrometheusConversationEventOutboxMetrics.render(
                new ConversationEventOutboxStatus(9, 2, 3, 4, 5, 7,
                        Optional.of(observed.minusSeconds(30))), observed);

        assertTrue(rendered.contains("chat_gateway_outbox_unpublished 9\n"));
        assertTrue(rendered.contains("chat_gateway_outbox_ready 2\n"));
        assertTrue(rendered.contains("chat_gateway_outbox_leased 3\n"));
        assertTrue(rendered.contains("chat_gateway_outbox_delayed 4\n"));
        assertTrue(rendered.contains("chat_gateway_outbox_retried 5\n"));
        assertTrue(rendered.contains("chat_gateway_outbox_maximum_attempt_count 7\n"));
        assertTrue(rendered.contains("chat_gateway_outbox_oldest_age_seconds 30\n"));
        assertFalse(rendered.contains("{"));
        assertFalse(rendered.contains("conversation"));
        assertFalse(rendered.contains("event_id"));
    }
}
