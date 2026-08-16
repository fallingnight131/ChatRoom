package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fallingnight.chat.application.notification.ProtectedWebPushSubscription;
import com.fallingnight.chat.application.notification.WebPushKeyCustodyPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import com.fallingnight.chat.application.security.SecretBytes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

final class AesGcmWebPushCredentialProtectorTest {
    private static final UUID ACCOUNT = UUID.fromString(
            "00000000-0000-4000-8000-000000000001");
    private static final UUID INSTALLATION = UUID.fromString(
            "00000000-0000-4000-8000-000000000002");
    private static final byte[] ENDPOINT = "https://push.example.test/send/opaque"
            .getBytes(StandardCharsets.US_ASCII);

    @Test
    void roundTripsContextBoundCredentialsWithStableKeyedLookupAndFreshCiphertext() {
        AtomicInteger nonce = new AtomicInteger();
        try (var custody = new TestKeyCustody();
                var registration = registration(ACCOUNT, INSTALLATION)) {
            var protector = new AesGcmWebPushCredentialProtector(custody, () -> {
                byte[] value = new byte[12];
                value[11] = (byte) nonce.incrementAndGet();
                return value;
            });
            try (var first = protector.protect(registration);
                    var second = protector.protect(registration)) {
                Snapshot firstBytes = snapshot(first);
                Snapshot secondBytes = snapshot(second);
                assertEquals("enc-v1", first.encryptionKeyId());
                assertFalse(Arrays.equals(ENDPOINT, firstBytes.endpoint()));
                assertNotEquals(Arrays.toString(firstBytes.endpoint()),
                        Arrays.toString(secondBytes.endpoint()));
                assertArrayEquals(firstBytes.lookupTag(), secondBytes.lookupTag());

                try (var restored = protector.unprotect(first)) {
                    assertEquals(ACCOUNT, restored.accountId());
                    assertEquals(INSTALLATION, restored.installationId());
                    assertEquals(Optional.of(Instant.parse("2026-08-18T00:00:00Z")),
                            restored.browserExpiresAt());
                    assertArrayEquals(ENDPOINT,
                            restored.withEndpointCopy(value -> value.clone()));
                    assertArrayEquals(p256dh(),
                            restored.withP256dhCopy(value -> value.clone()));
                    assertArrayEquals(auth(),
                            restored.withAuthSecretCopy(value -> value.clone()));
                }

                custody.activeKeyId = "enc-v2";
                try (var rotated = protector.protect(registration);
                        var oldRestored = protector.unprotect(first)) {
                    assertEquals("enc-v2", rotated.encryptionKeyId());
                    assertEquals(ACCOUNT, oldRestored.accountId());
                }
            }
        }
    }

    @Test
    void rejectsCiphertextMovedAcrossAccountContextOrChangedInStorage() {
        try (var custody = new TestKeyCustody();
                var registration = registration(ACCOUNT, INSTALLATION)) {
            AtomicInteger nonce = new AtomicInteger();
            var protector = new AesGcmWebPushCredentialProtector(custody, () -> {
                byte[] value = new byte[12]; value[11] = (byte) nonce.incrementAndGet();
                return value;
            });
            try (var original = protector.protect(registration)) {
                Snapshot bytes = snapshot(original);
                try (var moved = ProtectedWebPushSubscription.copyOf(
                        UUID.randomUUID(), INSTALLATION, original.browserExpiresAt(),
                        original.encryptionKeyId(), bytes.endpoint(), bytes.p256dh(),
                        bytes.auth(), bytes.lookupTag())) {
                    assertThrows(WebPushCredentialProtectionException.class,
                            () -> protector.unprotect(moved));
                }
                bytes.endpoint()[bytes.endpoint().length - 1] ^= 1;
                try (var changed = ProtectedWebPushSubscription.copyOf(
                        ACCOUNT, INSTALLATION, original.browserExpiresAt(),
                        original.encryptionKeyId(), bytes.endpoint(), bytes.p256dh(),
                        bytes.auth(), bytes.lookupTag())) {
                    assertThrows(WebPushCredentialProtectionException.class,
                            () -> protector.unprotect(changed));
                }
            }
        }
    }

    @Test
    void rejectsClosedCustodyKeys() {
        try (var custody = new TestKeyCustody();
                var registration = registration(ACCOUNT, INSTALLATION)) {
            custody.keys.get("enc-v1").close();
            assertThrows(IllegalStateException.class,
                    () -> new AesGcmWebPushCredentialProtector(custody).protect(registration));
        }
    }

    private static WebPushSubscriptionRegistration registration(
            UUID account, UUID installation) {
        return WebPushSubscriptionRegistration.copyOf(
                account, installation,
                Optional.of(Instant.parse("2026-08-18T00:00:00Z")),
                ENDPOINT, p256dh(), auth());
    }

    private static Snapshot snapshot(ProtectedWebPushSubscription value) {
        try {
            return value.withCopies((endpoint, p256dh, auth, tag) -> new Snapshot(
                    endpoint.clone(), p256dh.clone(), auth.clone(), tag.clone()));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] p256dh() {
        byte[] value = new byte[65]; value[0] = 0x04; return value;
    }

    private static byte[] auth() {
        byte[] value = new byte[16]; Arrays.fill(value, (byte) 7); return value;
    }

    private record Snapshot(byte[] endpoint, byte[] p256dh, byte[] auth, byte[] lookupTag) { }

    private static final class TestKeyCustody implements WebPushKeyCustodyPort, AutoCloseable {
        private final Map<String, SecretBytes> keys = new HashMap<>();
        private final SecretBytes lookupKey = SecretBytes.copyOf(bytes(9));
        private String activeKeyId = "enc-v1";

        private TestKeyCustody() {
            keys.put("enc-v1", SecretBytes.copyOf(bytes(1)));
            keys.put("enc-v2", SecretBytes.copyOf(bytes(2)));
        }

        @Override
        public <T> T withActiveEncryptionKey(BiFunction<String, byte[], T> action) {
            return key(activeKeyId).withCopy(value -> action.apply(activeKeyId, value));
        }

        @Override
        public <T> T withEncryptionKey(String keyId, Function<byte[], T> action) {
            return key(keyId).withCopy(action);
        }

        @Override
        public <T> T withEndpointLookupKey(Function<byte[], T> action) {
            return lookupKey.withCopy(action);
        }

        @Override
        public void close() {
            keys.values().forEach(SecretBytes::close);
            lookupKey.close();
        }

        private SecretBytes key(String keyId) {
            SecretBytes value = keys.get(keyId);
            if (value == null) throw new IllegalArgumentException("unknown fixture key");
            return value;
        }

        private static byte[] bytes(int value) {
            byte[] result = new byte[32]; Arrays.fill(result, (byte) value); return result;
        }
    }
}
