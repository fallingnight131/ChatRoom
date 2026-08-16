package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fallingnight.chat.application.notification.WebPushSubscriptionAdmissionDecision;
import com.fallingnight.chat.application.notification.WebPushSubscriptionMutationAction;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class InMemoryWebPushSubscriptionAdmissionTest {
    @Test
    void limitsExactTupleAndResetsAtWindowBoundary() {
        var admission = new InMemoryWebPushSubscriptionAdmission(
                new WebPushSubscriptionAdmissionLimits(Duration.ofMinutes(1), 2, 16));
        UUID account = UUID.randomUUID();
        UUID installation = UUID.randomUUID();
        Instant start = Instant.parse("2026-08-17T00:00:00Z");
        assertEquals(WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE,
                admission.admit(account, installation,
                        WebPushSubscriptionMutationAction.REPLACE, start));
        assertEquals(WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE,
                admission.admit(account, installation,
                        WebPushSubscriptionMutationAction.REPLACE, start.plusSeconds(10)));
        var limited = assertInstanceOf(
                WebPushSubscriptionAdmissionDecision.RateLimited.class,
                admission.admit(account, installation,
                        WebPushSubscriptionMutationAction.REPLACE, start.plusSeconds(20)));
        assertEquals(Duration.ofSeconds(40), limited.retryAfter());
        assertEquals(WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE,
                admission.admit(account, installation,
                        WebPushSubscriptionMutationAction.DELETE, start.plusSeconds(20)));
        assertEquals(WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE,
                admission.admit(account, installation,
                        WebPushSubscriptionMutationAction.REPLACE, start.plusSeconds(60)));
    }

    @Test
    void failsClosedAtCapacityAndReclaimsExpiredKeys() {
        var admission = new InMemoryWebPushSubscriptionAdmission(
                new WebPushSubscriptionAdmissionLimits(Duration.ofSeconds(10), 1, 16));
        Instant start = Instant.parse("2026-08-17T00:00:00Z");
        for (int index = 0; index < 16; index++) {
            assertEquals(WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE,
                    admission.admit(UUID.randomUUID(), UUID.randomUUID(),
                            WebPushSubscriptionMutationAction.REPLACE, start));
        }
        assertInstanceOf(WebPushSubscriptionAdmissionDecision.RateLimited.class,
                admission.admit(UUID.randomUUID(), UUID.randomUUID(),
                        WebPushSubscriptionMutationAction.REPLACE, start));
        assertEquals(WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE,
                admission.admit(UUID.randomUUID(), UUID.randomUUID(),
                        WebPushSubscriptionMutationAction.REPLACE, start.plusSeconds(10)));
        assertEquals(1, admission.trackedKeys());
    }
}
