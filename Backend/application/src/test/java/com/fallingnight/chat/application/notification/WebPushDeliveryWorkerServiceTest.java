package com.fallingnight.chat.application.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WebPushDeliveryWorkerServiceTest {
    private static final UUID SENDER = UUID.fromString(
            "00000000-0000-4000-8000-000000000001");
    private static final UUID FIRST = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString(
            "20000000-0000-4000-8000-000000000001");
    private static final UUID CONVERSATION = UUID.fromString(
            "00000000-0000-4000-8000-000000000003");
    private static final UUID MESSAGE = UUID.fromString(
            "00000000-0000-4000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void deliversGenericCommandsDeletesInvalidInstallAndCompletesFencedEvent() {
        UUID firstInstall = UUID.randomUUID(), secondInstall = UUID.randomUUID();
        FakeOutbox outbox = new FakeOutbox();
        FakeSubscriptions mutations = new FakeSubscriptions();
        AtomicReference<WebPushSubscriptionRegistration> retained = new AtomicReference<>();
        var service = service(new WebPushDeliveryPolicy(true),
                complete(List.of(new WebPushRecipient(FIRST, true),
                        new WebPushRecipient(SECOND, false))),
                account -> batch(account, account.equals(FIRST) ? firstInstall : secondInstall),
                command -> {
                    retained.set(command.registration());
                    assertTrue(command.toString().contains("credentials=REDACTED"));
                    assertEquals(MESSAGE, command.notificationId());
                    return command.registration().accountId().equals(FIRST)
                            ? WebPushProviderResult.DELIVERED
                            : WebPushProviderResult.INVALID_SUBSCRIPTION;
                }, mutations, outbox);

        WebPushWorkerReport report = service.process(claim(), NOW.plusSeconds(1));

        assertEquals(WebPushWorkerReport.Status.COMPLETED, report.status());
        assertEquals(2, report.providerAttempts());
        assertEquals(1, report.delivered());
        assertEquals(1, report.invalidSubscriptions());
        assertEquals(WebPushTerminalOutcome.DELIVERED, outbox.completedOutcome.get());
        assertEquals(List.of(secondInstall), mutations.deletedInstallations);
        assertThrows(IllegalStateException.class,
                () -> retained.get().withEndpointCopy(value -> value.length));
    }

    @Test
    void defersTransientProviderFailureWithStableFailureCode() {
        FakeOutbox outbox = new FakeOutbox();
        var service = service(new WebPushDeliveryPolicy(true),
                complete(List.of(new WebPushRecipient(FIRST, false))),
                account -> batch(account, UUID.randomUUID()),
                command -> WebPushProviderResult.TRANSIENT_FAILURE,
                new FakeSubscriptions(), outbox);

        WebPushWorkerReport report = service.process(claim(), NOW.plusSeconds(1));

        assertEquals(WebPushWorkerReport.Status.DEFERRED, report.status());
        assertEquals("PROVIDER_TRANSIENT", outbox.deferredFailureCode.get());
        assertEquals(NOW.plusSeconds(3), outbox.retryAt.get());
    }

    @Test
    void convertsProviderExceptionToSecretFreeTransientRetry() {
        FakeOutbox outbox = new FakeOutbox();
        var service = service(new WebPushDeliveryPolicy(true),
                complete(List.of(new WebPushRecipient(FIRST, false))),
                account -> batch(account, UUID.randomUUID()),
                command -> { throw new IllegalStateException("provider response secret"); },
                new FakeSubscriptions(), outbox);

        WebPushWorkerReport report = service.process(claim(), NOW.plusSeconds(1));

        assertEquals(WebPushWorkerReport.Status.DEFERRED, report.status());
        assertEquals("PROVIDER_TRANSIENT", outbox.deferredFailureCode.get());
    }

    @Test
    void reauthorizesBeforeAttemptAndCompletesIneligibleWithoutDecrypting() {
        FakeOutbox outbox = new FakeOutbox();
        AtomicInteger unprotected = new AtomicInteger();
        WebPushRecipientPolicyPort policy = new WebPushRecipientPolicyPort() {
            @Override
            public WebPushRecipientResolution resolve(
                    WebPushNotificationIntent intent, int limit) {
                return complete(List.of(new WebPushRecipient(FIRST, false)));
            }

            @Override
            public Optional<WebPushRecipient> reauthorize(
                    WebPushNotificationIntent intent, UUID recipientAccountId) {
                return Optional.empty();
            }
        };
        var service = new WebPushDeliveryWorkerService(
                new WebPushDeliveryPolicy(true), policy,
                (account, at) -> batch(account, UUID.randomUUID()),
                protectedSubscription -> {
                    unprotected.incrementAndGet();
                    return registration(protectedSubscription.accountId(),
                            protectedSubscription.installationId());
                }, command -> WebPushProviderResult.DELIVERED,
                new FakeSubscriptions(), outbox,
                (claim, failedAt) -> failedAt.plusSeconds(2), new WebPushWorkerEventSink() { });

        WebPushWorkerReport report = service.process(claim(), NOW.plusSeconds(1));

        assertEquals(WebPushWorkerReport.Status.COMPLETED, report.status());
        assertEquals(0, report.providerAttempts());
        assertEquals(1, report.ineligibleRecipients());
        assertEquals(0, unprotected.get());
        assertEquals(WebPushTerminalOutcome.INELIGIBLE, outbox.completedOutcome.get());
    }

    @Test
    void exactDefaultOffDoesNothingAndSaturationDefersWithoutLoadingSecrets() {
        AtomicInteger resolutions = new AtomicInteger();
        FakeOutbox disabledOutbox = new FakeOutbox();
        WebPushRecipientPolicyPort saturated = new WebPushRecipientPolicyPort() {
            @Override
            public WebPushRecipientResolution resolve(
                    WebPushNotificationIntent intent, int limit) {
                resolutions.incrementAndGet();
                return WebPushRecipientResolution.Saturated.INSTANCE;
            }

            @Override
            public Optional<WebPushRecipient> reauthorize(
                    WebPushNotificationIntent intent, UUID recipientAccountId) {
                return Optional.empty();
            }
        };
        var disabled = baseService(WebPushDeliveryPolicy.DEFAULT, saturated,
                (account, at) -> { throw new AssertionError("must not load"); },
                command -> WebPushProviderResult.DELIVERED, disabledOutbox);
        assertEquals(WebPushWorkerReport.Status.DISABLED,
                disabled.process(claim(), NOW.plusSeconds(1)).status());
        assertEquals(0, resolutions.get());

        FakeOutbox enabledOutbox = new FakeOutbox();
        var enabled = baseService(new WebPushDeliveryPolicy(true), saturated,
                (account, at) -> { throw new AssertionError("must not load"); },
                command -> WebPushProviderResult.DELIVERED, enabledOutbox);
        assertEquals(WebPushWorkerReport.Status.DEFERRED,
                enabled.process(claim(), NOW.plusSeconds(1)).status());
        assertEquals("RECIPIENT_SATURATED", enabledOutbox.deferredFailureCode.get());
    }

    private static WebPushDeliveryWorkerService service(
            WebPushDeliveryPolicy deliveryPolicy,
            WebPushRecipientResolution resolution,
            SubscriptionBatchFactory batches,
            WebPushProviderPort provider,
            FakeSubscriptions mutations,
            FakeOutbox outbox) {
        return baseService(deliveryPolicy, new WebPushRecipientPolicyPort() {
            @Override
            public WebPushRecipientResolution resolve(
                    WebPushNotificationIntent intent, int limit) {
                return resolution;
            }

            @Override
            public Optional<WebPushRecipient> reauthorize(
                    WebPushNotificationIntent intent, UUID recipientAccountId) {
                return ((WebPushRecipientResolution.Complete) resolution).recipients().stream()
                        .filter(recipient -> recipient.accountId().equals(recipientAccountId))
                        .findFirst();
            }
        }, (account, at) -> batches.create(account), provider, mutations, outbox);
    }

    private static WebPushDeliveryWorkerService baseService(
            WebPushDeliveryPolicy deliveryPolicy,
            WebPushRecipientPolicyPort policy,
            WebPushProtectedSubscriptionPort subscriptions,
            WebPushProviderPort provider,
            FakeOutbox outbox) {
        return baseService(deliveryPolicy, policy, subscriptions, provider,
                new FakeSubscriptions(), outbox);
    }

    private static WebPushDeliveryWorkerService baseService(
            WebPushDeliveryPolicy deliveryPolicy,
            WebPushRecipientPolicyPort policy,
            WebPushProtectedSubscriptionPort subscriptions,
            WebPushProviderPort provider,
            FakeSubscriptions mutations,
            FakeOutbox outbox) {
        return new WebPushDeliveryWorkerService(
                deliveryPolicy, policy, subscriptions,
                protectedSubscription -> registration(
                        protectedSubscription.accountId(),
                        protectedSubscription.installationId()),
                provider, mutations, outbox,
                (claim, failedAt) -> failedAt.plusSeconds(2),
                new WebPushWorkerEventSink() { });
    }

    private static WebPushRecipientResolution.Complete complete(
            List<WebPushRecipient> recipients) {
        return new WebPushRecipientResolution.Complete(recipients);
    }

    private static ProtectedWebPushSubscriptionBatch batch(UUID account, UUID installation) {
        return new ProtectedWebPushSubscriptionBatch(
                account, List.of(protectedSubscription(account, installation)));
    }

    private static ProtectedWebPushSubscription protectedSubscription(
            UUID account, UUID installation) {
        return ProtectedWebPushSubscription.copyOf(
                account, installation, Optional.empty(), "fixture:v1",
                new byte[32], new byte[96], new byte[48], new byte[32]);
    }

    private static WebPushSubscriptionRegistration registration(
            UUID account, UUID installation) {
        byte[] p256dh = new byte[65]; p256dh[0] = 0x04;
        byte[] auth = new byte[16]; Arrays.fill(auth, (byte) 7);
        return WebPushSubscriptionRegistration.copyOf(
                account, installation, Optional.empty(),
                "https://push.example.test/send/opaque"
                        .getBytes(StandardCharsets.US_ASCII), p256dh, auth);
    }

    private static WebPushOutboxClaim claim() {
        var intent = new WebPushNotificationIntent(
                MESSAGE, CONVERSATION, SENDER, NOW, NOW.plus(Duration.ofHours(1)), Set.of(FIRST));
        return new WebPushOutboxClaim(
                intent, UUID.randomUUID(), UUID.randomUUID(), NOW,
                NOW.plusSeconds(30), 1);
    }

    @FunctionalInterface
    private interface SubscriptionBatchFactory {
        ProtectedWebPushSubscriptionBatch create(UUID accountId);
    }

    private static final class FakeSubscriptions implements WebPushSubscriptionPort {
        private final List<UUID> deletedInstallations = new ArrayList<>();

        @Override
        public WebPushSubscriptionReplaceResult replace(
                WebPushSubscriptionRegistration registration) {
            return WebPushSubscriptionReplaceResult.REPLACED;
        }

        @Override
        public boolean delete(UUID accountId, UUID installationId) {
            deletedInstallations.add(installationId);
            return true;
        }
    }

    private static final class FakeOutbox implements WebPushOutboxPort {
        private final AtomicReference<WebPushTerminalOutcome> completedOutcome =
                new AtomicReference<>();
        private final AtomicReference<String> deferredFailureCode = new AtomicReference<>();
        private final AtomicReference<Instant> retryAt = new AtomicReference<>();

        @Override
        public List<WebPushOutboxClaim> claim(
                UUID owner, Instant claimedAt, Duration lease, int limit) {
            return List.of();
        }

        @Override
        public boolean complete(WebPushOutboxClaim claim, Instant completedAt,
                WebPushTerminalOutcome outcome) {
            completedOutcome.set(outcome);
            return true;
        }

        @Override
        public boolean defer(WebPushOutboxClaim claim, Instant failedAt,
                Instant retryAt, String failureCode) {
            this.retryAt.set(retryAt);
            deferredFailureCode.set(failureCode);
            return true;
        }

        @Override public int expire(Instant observedAt, int limit) { return 0; }
        @Override public int purgeCompletedBefore(Instant cutoff, int limit) { return 0; }
    }
}
