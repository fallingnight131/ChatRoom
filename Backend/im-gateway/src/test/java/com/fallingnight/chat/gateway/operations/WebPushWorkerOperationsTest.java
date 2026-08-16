package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushNotificationIntent;
import com.fallingnight.chat.application.notification.WebPushOutboxClaim;
import com.fallingnight.chat.application.notification.WebPushTerminalOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WebPushWorkerOperationsTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void rendersOnlyFixedIdentityFreeMetrics() {
        WebPushWorkerTelemetry telemetry = new WebPushWorkerTelemetry();
        telemetry.delivered(); telemetry.invalidSubscription();
        telemetry.transientFailure(); telemetry.authenticationFailure();
        telemetry.ineligible(); telemetry.recipientSaturated(); telemetry.deferred();
        telemetry.completed(WebPushTerminalOutcome.DELIVERED);
        telemetry.completed(WebPushTerminalOutcome.INELIGIBLE);
        telemetry.fenceLost();

        String rendered = PrometheusWebPushWorkerMetrics.render(telemetry.snapshot());

        assertTrue(rendered.contains("chat_gateway_web_push_worker_delivered_total 1"));
        assertTrue(rendered.contains("chat_gateway_web_push_worker_fence_lost_total 1"));
        assertFalse(rendered.contains("{"));
        assertFalse(rendered.contains("account"));
        assertFalse(rendered.contains("message"));
        assertFalse(rendered.contains("conversation"));
        assertFalse(rendered.contains("endpoint"));
    }

    @Test
    void appliesDeterministicExponentialJitterAndClipsBeforeExpiry() {
        var minimum = new ExponentialWebPushRetrySchedule(() -> 500);
        var maximum = new ExponentialWebPushRetrySchedule(() -> 1_500);
        assertEquals(NOW.plusMillis(500), minimum.nextRetry(claim(1, 60), NOW));
        assertEquals(NOW.plusSeconds(6), maximum.nextRetry(claim(3, 60), NOW));
        assertEquals(NOW.plusMillis(999),
                maximum.nextRetry(claim(20, 1), NOW));
        assertThrows(IllegalStateException.class,
                () -> new ExponentialWebPushRetrySchedule(() -> 499)
                        .nextRetry(claim(1, 60), NOW));
    }

    private static WebPushOutboxClaim claim(int attempt, long expirySeconds) {
        UUID message = UUID.randomUUID();
        var intent = new WebPushNotificationIntent(
                message, UUID.randomUUID(), UUID.randomUUID(),
                NOW.minusSeconds(1), NOW.plusSeconds(expirySeconds), Set.of());
        return new WebPushOutboxClaim(
                intent, UUID.randomUUID(), UUID.randomUUID(), NOW,
                NOW.plusMillis(Math.min(30_000, expirySeconds * 1_000)), attempt);
    }
}
