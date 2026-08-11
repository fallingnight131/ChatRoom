package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fallingnight.chat.application.identity.CredentialVerification;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Argon2idCredentialHasherTest {
    @Test
    void createsLibsodiumFormatThatTheCompatibilityVerifierAccepts() {
        AtomicInteger salt = new AtomicInteger();
        Argon2idCredentialHasher hasher = new Argon2idCredentialHasher(() -> {
            byte[] value = new byte[16];
            value[15] = (byte) salt.incrementAndGet();
            return value;
        });
        byte[] password = "legacy-password-密码".getBytes(StandardCharsets.UTF_8);
        StoredCredential.Argon2id first = hasher.hash(password);
        StoredCredential.Argon2id second = hasher.hash(password);

        assertNotEquals(first, second);
        CompatibleCredentialVerifier verifier = new CompatibleCredentialVerifier();
        assertEquals(CredentialVerification.VERIFIED,
                verifier.verifyOrDummy(password, Optional.of(first)));
        assertEquals(CredentialVerification.VERIFIED,
                verifier.verifyOrDummy(password, Optional.of(second)));
    }
}
