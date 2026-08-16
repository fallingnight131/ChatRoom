package com.fallingnight.chat.application.notification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WebPushSubscriptionMutationServiceTest {
    private static final UUID ACCOUNT = UUID.fromString(
            "00000000-0000-4000-8000-000000000001");
    private static final UUID INSTALLATION = UUID.fromString(
            "00000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void consumesSecretsAndBindsOnlyTheAuthenticatedAccount() {
        AtomicReference<UUID> storedAccount = new AtomicReference<>();
        AtomicReference<byte[]> storedEndpoint = new AtomicReference<>();
        var service = service(new WebPushDeliveryPolicy(true), allowed(),
                new FakeSubscriptions() {
                    @Override
                    public WebPushSubscriptionReplaceResult replace(
                            WebPushSubscriptionRegistration registration) {
                        storedAccount.set(registration.accountId());
                        storedEndpoint.set(registration.withEndpointCopy(value -> value.clone()));
                        return WebPushSubscriptionReplaceResult.REPLACED;
                    }
                });
        byte[] endpoint = endpoint();
        var request = WebPushSubscriptionMutationRequest.copyOf(
                INSTALLATION, Optional.empty(), endpoint, p256dh(), auth());
        Arrays.fill(endpoint, (byte) 0);

        var result = service.replace(ACCOUNT, request);

        assertEquals(WebPushSubscriptionMutationResult.Outcome.REPLACED, result.outcome());
        assertEquals(ACCOUNT, storedAccount.get());
        assertArrayEquals(endpoint(), storedEndpoint.get());
        assertTrue(request.isClosed());
        assertFalse(request.toString().contains("opaque-token"));
    }

    @Test
    void defaultOffClosesRequestWithoutAdmissionOrPersistence() {
        AtomicInteger admissions = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        var service = service(WebPushDeliveryPolicy.DEFAULT,
                (account, installation, action, at) -> {
                    admissions.incrementAndGet();
                    return WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE;
                }, new FakeSubscriptions() {
                    @Override
                    public WebPushSubscriptionReplaceResult replace(
                            WebPushSubscriptionRegistration registration) {
                        writes.incrementAndGet();
                        return WebPushSubscriptionReplaceResult.REPLACED;
                    }
                });
        var request = request();

        var result = service.replace(ACCOUNT, request);

        assertEquals(WebPushSubscriptionMutationResult.Outcome.DISABLED, result.outcome());
        assertEquals(0, admissions.get());
        assertEquals(0, writes.get());
        assertTrue(request.isClosed());
    }

    @Test
    void rateLimitPrecedesProtectionAndReturnsBoundedRetry() {
        AtomicInteger writes = new AtomicInteger();
        var service = service(new WebPushDeliveryPolicy(true),
                (account, installation, action, at) ->
                        new WebPushSubscriptionAdmissionDecision.RateLimited(
                                Duration.ofSeconds(30)),
                new FakeSubscriptions() {
                    @Override
                    public WebPushSubscriptionReplaceResult replace(
                            WebPushSubscriptionRegistration registration) {
                        writes.incrementAndGet();
                        return WebPushSubscriptionReplaceResult.REPLACED;
                    }
                });
        var request = request();

        var result = service.replace(ACCOUNT, request);

        assertEquals(WebPushSubscriptionMutationResult.Outcome.RATE_LIMITED, result.outcome());
        assertEquals(Duration.ofSeconds(30), result.retryAfter().orElseThrow());
        assertEquals(0, writes.get());
        assertTrue(request.isClosed());
    }

    @Test
    void mapsQuotaAvailabilityAndScopedDeleteWithoutSecretResults() {
        AtomicReference<WebPushSubscriptionReplaceResult> replacement =
                new AtomicReference<>(WebPushSubscriptionReplaceResult.LIMIT_REACHED);
        AtomicReference<Boolean> delete = new AtomicReference<>(false);
        var service = service(new WebPushDeliveryPolicy(true), allowed(),
                new FakeSubscriptions() {
                    @Override
                    public WebPushSubscriptionReplaceResult replace(
                            WebPushSubscriptionRegistration registration) {
                        return replacement.get();
                    }

                    @Override
                    public boolean delete(UUID accountId, UUID installationId) {
                        assertEquals(ACCOUNT, accountId);
                        assertEquals(INSTALLATION, installationId);
                        return delete.get();
                    }
                });
        assertEquals(WebPushSubscriptionMutationResult.Outcome.LIMIT_REACHED,
                service.replace(ACCOUNT, request()).outcome());
        replacement.set(WebPushSubscriptionReplaceResult.ACCOUNT_UNAVAILABLE);
        assertEquals(WebPushSubscriptionMutationResult.Outcome.ACCOUNT_UNAVAILABLE,
                service.replace(ACCOUNT, request()).outcome());
        assertEquals(WebPushSubscriptionMutationResult.Outcome.UNCHANGED,
                service.delete(ACCOUNT, INSTALLATION).outcome());
        delete.set(true);
        assertEquals(WebPushSubscriptionMutationResult.Outcome.DELETED,
                service.delete(ACCOUNT, INSTALLATION).outcome());
    }

    @Test
    void closesRequestWhenProtectionOrPersistenceFails() {
        var service = service(new WebPushDeliveryPolicy(true), allowed(),
                new FakeSubscriptions() {
                    @Override
                    public WebPushSubscriptionReplaceResult replace(
                            WebPushSubscriptionRegistration registration) {
                        throw new IllegalStateException("fixture persistence failure");
                    }
                });
        var request = request();

        assertThrows(IllegalStateException.class, () -> service.replace(ACCOUNT, request));
        assertTrue(request.isClosed());
    }

    private static WebPushSubscriptionMutationService service(
            WebPushDeliveryPolicy policy,
            WebPushSubscriptionAdmissionPort admission,
            WebPushSubscriptionPort subscriptions) {
        return new WebPushSubscriptionMutationService(policy, admission, subscriptions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WebPushSubscriptionAdmissionPort allowed() {
        return (account, installation, action, at) -> {
            assertEquals(NOW, at);
            return WebPushSubscriptionAdmissionDecision.Allowed.INSTANCE;
        };
    }

    private static WebPushSubscriptionMutationRequest request() {
        return WebPushSubscriptionMutationRequest.copyOf(
                INSTALLATION, Optional.empty(), endpoint(), p256dh(), auth());
    }

    private static byte[] endpoint() {
        return "https://push.example.test/send/opaque-token"
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] p256dh() {
        byte[] value = new byte[65]; value[0] = 0x04; return value;
    }

    private static byte[] auth() {
        byte[] value = new byte[16]; Arrays.fill(value, (byte) 7); return value;
    }

    private static class FakeSubscriptions implements WebPushSubscriptionPort {
        @Override
        public WebPushSubscriptionReplaceResult replace(
                WebPushSubscriptionRegistration registration) {
            return WebPushSubscriptionReplaceResult.REPLACED;
        }

        @Override
        public boolean delete(UUID accountId, UUID installationId) { return false; }
    }
}
