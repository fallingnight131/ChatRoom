package com.fallingnight.chat.application.notification;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WebPushApplicationBoundaryTest {
    private static final UUID ACCOUNT_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000001");
    private static final UUID INSTALLATION_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000002");
    private static final UUID MESSAGE_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000003");
    private static final UUID CONVERSATION_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000004");

    @Test
    void ownsClearsAndRedactsSubscriptionCredentials() {
        byte[] endpoint = "https://push.example.test/send/opaque-token"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] p256dh = validP256dh();
        byte[] auth = new byte[16];
        Arrays.fill(auth, (byte) 9);
        var registration = WebPushSubscriptionRegistration.copyOf(
                ACCOUNT_ID, INSTALLATION_ID, Optional.empty(), endpoint, p256dh, auth);
        Arrays.fill(endpoint, (byte) 1);
        Arrays.fill(p256dh, (byte) 2);
        Arrays.fill(auth, (byte) 3);

        AtomicReference<byte[]> retained = new AtomicReference<>();
        assertEquals("https://push.example.test/send/opaque-token",
                registration.withEndpointCopy(copy -> {
                    retained.set(copy);
                    return new String(copy, StandardCharsets.US_ASCII);
                }));
        assertTrue(registration.toString().contains("credentials=REDACTED"));
        assertFalse(registration.toString().contains("opaque-token"));
        assertArrayEquals(new byte[retained.get().length], retained.get());

        registration.close();
        assertTrue(registration.isClosed());
        assertThrows(IllegalStateException.class,
                () -> registration.withAuthSecretCopy(copy -> copy.length));
    }

    @Test
    void rejectsNonCanonicalEndpointsAndMalformedKeys() {
        byte[] key = validP256dh();
        byte[] auth = new byte[16];
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "http://push.example.test/send/a", key, auth));
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "https://Push.example.test/send/a", key, auth));
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "https://push.example.test:443/send/a", key, auth));
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "https://user@push.example.test/send/a", key, auth));
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "https://push.example.test/a/../send/a", key, auth));
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "https://push.example.test/send/a#secret", key, auth));
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "https://push.example.test/send/a", new byte[64], auth));
        key[0] = 3;
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "https://push.example.test/send/a", key, auth));
        assertThrows(IllegalArgumentException.class, () -> credentials(
                "https://push.example.test/send/a", validP256dh(), new byte[15]));
    }

    @Test
    void keepsStablePayloadFreeIdentityWithinTwentyFourHours() {
        Instant committedAt = Instant.parse("2026-08-17T00:00:00Z");
        UUID mentioned = UUID.fromString("00000000-0000-4000-8000-000000000005");
        var intent = new WebPushNotificationIntent(
                MESSAGE_ID,
                CONVERSATION_ID,
                ACCOUNT_ID,
                committedAt,
                committedAt.plus(WebPushNotificationIntent.MAX_LIFETIME),
                Set.of(mentioned));

        assertEquals(MESSAGE_ID, intent.messageId());
        assertEquals(Set.of(mentioned), intent.mentionedAccountIds());
        assertFalse(WebPushDeliveryPolicy.DEFAULT.enabled());
        assertThrows(UnsupportedOperationException.class,
                () -> intent.mentionedAccountIds().add(INSTALLATION_ID));
        assertThrows(IllegalArgumentException.class, () -> new WebPushNotificationIntent(
                MESSAGE_ID,
                CONVERSATION_ID,
                ACCOUNT_ID,
                committedAt,
                committedAt.plusSeconds(86_401),
                Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new WebPushNotificationIntent(
                MESSAGE_ID,
                CONVERSATION_ID,
                ACCOUNT_ID,
                committedAt,
                committedAt.plusSeconds(60),
                Set.of(ACCOUNT_ID)));
        var tooManyMentions = new java.util.HashSet<UUID>();
        for (int index = 0; index <= WebPushNotificationIntent.MAX_MENTIONED_ACCOUNTS; index++) {
            tooManyMentions.add(new UUID(1, index));
        }
        assertThrows(IllegalArgumentException.class, () -> new WebPushNotificationIntent(
                MESSAGE_ID,
                CONVERSATION_ID,
                ACCOUNT_ID,
                committedAt,
                committedAt.plusSeconds(60),
                tooManyMentions));
    }

    @Test
    void ownsAndClearsProtectedSubscriptionMaterial() throws Exception {
        byte[] endpointCiphertext = new byte[32];
        byte[] p256dhCiphertext = new byte[96];
        byte[] authCiphertext = new byte[48];
        byte[] lookupTag = new byte[32];
        Arrays.fill(endpointCiphertext, (byte) 1);
        var protectedSubscription = ProtectedWebPushSubscription.copyOf(
                ACCOUNT_ID,
                INSTALLATION_ID,
                Optional.empty(),
                "test-key:v1",
                endpointCiphertext,
                p256dhCiphertext,
                authCiphertext,
                lookupTag);
        Arrays.fill(endpointCiphertext, (byte) 9);
        AtomicReference<byte[]> retained = new AtomicReference<>();
        int firstByte = protectedSubscription.withCopies((endpoint, key, auth, tag) -> {
            retained.set(endpoint);
            return Byte.toUnsignedInt(endpoint[0]);
        });
        assertEquals(1, firstByte);
        assertArrayEquals(new byte[32], retained.get());
        assertTrue(protectedSubscription.toString().contains("protectedBytes=REDACTED"));
        protectedSubscription.close();
        assertTrue(protectedSubscription.isClosed());
        assertThrows(IllegalStateException.class,
                () -> protectedSubscription.withCopies((endpoint, key, auth, tag) -> null));
    }

    @Test
    void boundsFencedOutboxClaimsToTheIntentLifetime() {
        Instant committedAt = Instant.parse("2026-08-17T00:00:00Z");
        var intent = new WebPushNotificationIntent(
                MESSAGE_ID, CONVERSATION_ID, ACCOUNT_ID, committedAt,
                committedAt.plusSeconds(60), Set.of());
        var claim = new WebPushOutboxClaim(
                intent, UUID.randomUUID(), UUID.randomUUID(), committedAt,
                committedAt.plusSeconds(30), 1);
        assertEquals(MESSAGE_ID, claim.intent().messageId());
        assertThrows(IllegalArgumentException.class, () -> new WebPushOutboxClaim(
                intent, UUID.randomUUID(), UUID.randomUUID(), committedAt,
                committedAt.plusSeconds(61), 1));
        assertThrows(IllegalArgumentException.class, () -> new WebPushOutboxClaim(
                intent, UUID.randomUUID(), UUID.randomUUID(), committedAt,
                committedAt.plusSeconds(1), 0));
    }

    private static WebPushSubscriptionCredentials credentials(
            String endpoint, byte[] p256dh, byte[] auth) {
        return WebPushSubscriptionCredentials.copyOf(
                endpoint.getBytes(StandardCharsets.US_ASCII), p256dh, auth);
    }

    private static byte[] validP256dh() {
        byte[] key = new byte[65];
        key[0] = 0x04;
        return key;
    }
}
