package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.identity.CredentialVerification;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomPasswordEncoderTest {
    @Test void createsDifferentSlowHashesAndStableKeyedTag() {
        byte[] key = bytes(1); byte[] password = "secret-密码".getBytes(StandardCharsets.UTF_8);
        try (LegacyV1RoomPasswordEncoder encoder = new LegacyV1RoomPasswordEncoder(key)) {
            var first = encoder.hash(password); var second = encoder.hash(password);
            assertNotEquals(first.encodedHash(), second.encodedHash());
            assertEquals(first.idempotencyTag(), second.idempotencyTag());
            assertTrue(first.idempotencyTag().matches(
                    "^hmac-sha256:v1:[A-Za-z0-9_-]{43}$"));
            CompatibleCredentialVerifier verifier = new CompatibleCredentialVerifier();
            assertEquals(CredentialVerification.VERIFIED, verifier.verifyOrDummy(password,
                    Optional.of(new com.fallingnight.chat.application.identity.StoredCredential
                            .Argon2id(first.encodedHash()))));
        }
        assertArrayEquals(bytes(1), key);
    }

    @Test void separatesPasswordsKeysAndDomains() {
        var hasher = new Argon2idCredentialHasher(() -> new byte[16]);
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        try (var first = new LegacyV1RoomPasswordEncoder(bytes(1), hasher,
                    "domain-one\0".getBytes(StandardCharsets.US_ASCII));
                var otherKey = new LegacyV1RoomPasswordEncoder(bytes(2), hasher,
                    "domain-one\0".getBytes(StandardCharsets.US_ASCII));
                var otherDomain = new LegacyV1RoomPasswordEncoder(bytes(1), hasher,
                    "domain-two\0".getBytes(StandardCharsets.US_ASCII))) {
            String tag = first.hash(password).idempotencyTag();
            assertNotEquals(tag, first.hash("different".getBytes(StandardCharsets.UTF_8))
                    .idempotencyTag());
            assertNotEquals(tag, otherKey.hash(password).idempotencyTag());
            assertNotEquals(tag, otherDomain.hash(password).idempotencyTag());
        }
    }

    @Test void rejectsWrongKeyLengthAndUseAfterClose() {
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomPasswordEncoder(new byte[31]));
        LegacyV1RoomPasswordEncoder encoder = new LegacyV1RoomPasswordEncoder(bytes(3));
        encoder.close(); assertTrue(encoder.isClosed());
        assertThrows(IllegalStateException.class,
                () -> encoder.hash("secret".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[LegacyV1RoomPasswordEncoder.KEY_BYTES];
        Arrays.fill(result, (byte) value); return result;
    }
}
