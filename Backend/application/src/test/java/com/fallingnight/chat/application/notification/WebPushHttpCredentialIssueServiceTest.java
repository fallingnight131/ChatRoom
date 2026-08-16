package com.fallingnight.chat.application.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WebPushHttpCredentialIssueServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final AuthenticatedDeviceActor ACTOR = new AuthenticatedDeviceActor(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void remainsExactDefaultOffAndBindsTheAuthenticatedActorWhenEnabled() {
        AtomicInteger calls = new AtomicInteger();
        WebPushHttpCredentialIssuePort port = (actor, observedAt) -> {
            calls.incrementAndGet(); assertEquals(ACTOR, actor); assertEquals(NOW, observedAt);
            return Optional.of(credential(actor.sessionId()));
        };
        var disabled = new WebPushHttpCredentialIssueService(
                WebPushDeliveryPolicy.DEFAULT, port, clock());
        assertEquals(WebPushHttpCredentialIssueResult.Rejected.DISABLED,
                disabled.issue(ACTOR));
        assertEquals(0, calls.get());

        var enabled = new WebPushHttpCredentialIssueService(
                new WebPushDeliveryPolicy(true), port, clock());
        var result = (WebPushHttpCredentialIssueResult.Issued) enabled.issue(ACTOR);
        assertEquals(1, calls.get());
        try (IssuedWebPushHttpCredential issued = result.credential()) {
            assertEquals(ACTOR.sessionId(), issued.sessionId());
            assertFalse(issued.toString().contains("aaaaaaaa"));
            issued.withTokenCopies((bearer, csrf) -> {
                assertEquals(43, bearer.length); assertEquals(43, csrf.length); return null;
            });
        }
        assertTrue(result.credential().isClosed());
    }

    @Test
    void mapsAnUnavailableCurrentSessionWithoutCreatingSecrets() {
        var service = new WebPushHttpCredentialIssueService(
                new WebPushDeliveryPolicy(true), (actor, now) -> Optional.empty(), clock());
        assertEquals(WebPushHttpCredentialIssueResult.Rejected.SESSION_UNAVAILABLE,
                service.issue(ACTOR));
    }

    private static IssuedWebPushHttpCredential credential(UUID sessionId) {
        return IssuedWebPushHttpCredential.copyOf(sessionId,
                "a".repeat(43).getBytes(StandardCharsets.US_ASCII),
                "b".repeat(43).getBytes(StandardCharsets.US_ASCII),
                NOW.plusSeconds(600));
    }

    private static Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
}
