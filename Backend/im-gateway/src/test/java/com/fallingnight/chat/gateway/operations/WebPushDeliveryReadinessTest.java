package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushOutboxStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WebPushDeliveryReadinessTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void isHealthyAtExactBoundsAndUsesStableFailurePrecedence() {
        WebPushDeliveryReadinessPolicy policy = policy();
        WebPushOutboxStatus atBounds = status(100, 5, NOW.minusSeconds(60));
        assertEquals(WebPushDeliveryReadiness.healthy(),
                policy.evaluate(atBounds, loop(2), NOW));

        assertEquals(WebPushDeliveryReadiness.Reason.CONSECUTIVE_FAILURES,
                policy.evaluate(status(101, 6, NOW.minusSeconds(61)), loop(3), NOW).reason());
        assertEquals(WebPushDeliveryReadiness.Reason.EXPIRED_BACKLOG,
                policy.evaluate(status(100, 6, NOW.minusSeconds(60)), loop(0), NOW).reason());
        assertEquals(WebPushDeliveryReadiness.Reason.BACKLOG_COUNT,
                policy.evaluate(status(101, 5, NOW.minusSeconds(60)), loop(0), NOW).reason());
        assertEquals(WebPushDeliveryReadiness.Reason.BACKLOG_AGE,
                policy.evaluate(status(100, 5, NOW.minusSeconds(61)), loop(0), NOW).reason());
    }

    @Test
    void probeShortCircuitsStoppedAndFailsClosedWithoutDetails() {
        AtomicInteger statusCalls = new AtomicInteger();
        var stopped = new WebPushDeliveryReadinessProbe(
                () -> false,
                observedAt -> { statusCalls.incrementAndGet(); return status(0, 0, NOW); },
                () -> loop(0), policy(), fixedClock());
        assertEquals(WebPushDeliveryReadiness.Reason.STOPPED, stopped.check().reason());
        assertEquals(0, statusCalls.get());

        var failed = new WebPushDeliveryReadinessProbe(
                () -> true,
                observedAt -> { throw new IllegalStateException("private database detail"); },
                () -> loop(0), policy(), fixedClock());
        assertEquals(WebPushDeliveryReadiness.Reason.STATUS_UNAVAILABLE,
                failed.check().reason());
    }

    @Test
    void rendersOnlyFixedOneHotReadinessMetricsAndRejectsUnsafePolicy() {
        String rendered = PrometheusWebPushDeliveryReadinessMetrics.render(
                WebPushDeliveryReadiness.unready(
                        WebPushDeliveryReadiness.Reason.BACKLOG_AGE));
        assertTrue(rendered.contains("chat_gateway_web_push_delivery_ready 0\n"));
        assertTrue(rendered.contains("chat_gateway_web_push_delivery_reason_backlog_age 1\n"));
        assertTrue(rendered.contains("chat_gateway_web_push_delivery_reason_healthy 0\n"));
        assertFalse(rendered.contains("{"));
        assertFalse(rendered.contains("account"));
        assertThrows(IllegalArgumentException.class,
                () -> new WebPushDeliveryReadinessPolicy(
                        0, Duration.ofMinutes(1), 0, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new WebPushDeliveryReadinessPolicy(
                        100, Duration.ofHours(25), 0, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new WebPushDeliveryReadinessPolicy(
                        100, Duration.ofMillis(1500), 0, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new WebPushDeliveryReadiness(false,
                        WebPushDeliveryReadiness.Reason.HEALTHY));
    }

    private static WebPushDeliveryReadinessPolicy policy() {
        return new WebPushDeliveryReadinessPolicy(
                100, Duration.ofSeconds(60), 5, 3);
    }

    private static WebPushOutboxStatus status(
            long pending, long expired, Instant oldest) {
        if (pending == 0) {
            return new WebPushOutboxStatus(0, 0, 0, 0, 0, 0, 0, Optional.empty());
        }
        return new WebPushOutboxStatus(
                pending, pending - expired, 0, 0, expired,
                0, 1, Optional.of(oldest));
    }

    private static WebPushDeliveryLoopTelemetrySnapshot loop(int failures) {
        return new WebPushDeliveryLoopTelemetrySnapshot(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, failures, 0);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
