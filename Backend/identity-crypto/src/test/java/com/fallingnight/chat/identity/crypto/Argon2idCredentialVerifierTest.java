package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import com.fallingnight.chat.application.identity.CredentialVerification;
import com.fallingnight.chat.application.identity.StoredCredential;
import org.junit.jupiter.api.Test;

class Argon2idCredentialVerifierTest {
    private static final String LIBSODIUM_INTERACTIVE_HASH =
            "$argon2id$v=19$m=65536,t=2,p=1$E1wX9i9QVyERI3DZqWy0Kg$"
                    + "nDO9/91zFAJGLsvBZudV4nKX4eGGHWTwuimwcjPzPcw";
    private final Argon2idCredentialVerifier verifier = new Argon2idCredentialVerifier();

    @Test
    void verifiesTheV1LibsodiumInteractiveVector() {
        StoredCredential credential = new StoredCredential.Argon2id(LIBSODIUM_INTERACTIVE_HASH);
        assertEquals(CredentialVerification.VERIFIED, verifier.verifyOrDummy(
                bytes("java-v2-test-password"), Optional.of(credential)));
        assertEquals(CredentialVerification.REJECTED, verifier.verifyOrDummy(
                bytes("wrong-test-password"), Optional.of(credential)));
    }

    @Test
    void absentOrMalformedHashesStillRejectAfterDummyWork() {
        assertEquals(CredentialVerification.REJECTED,
                verifier.verifyOrDummy(bytes("test-password"), Optional.empty()));
        assertEquals(CredentialVerification.REJECTED, verifier.verifyOrDummy(
                bytes("test-password"),
                Optional.of(new StoredCredential.Argon2id(
                        "$argon2id$v=19$m=999999999,t=2,p=1$bad$bad"))));
    }

    @Test
    void parserRejectsUnsupportedAlgorithmsVersionsParametersAndLengths() {
        assertTrue(Argon2idCredentialVerifier.parse(LIBSODIUM_INTERACTIVE_HASH).isPresent());
        assertTrue(Argon2idCredentialVerifier.parse(
                LIBSODIUM_INTERACTIVE_HASH.replace("argon2id", "argon2i")).isEmpty());
        assertTrue(Argon2idCredentialVerifier.parse(
                LIBSODIUM_INTERACTIVE_HASH.replace("v=19", "v=16")).isEmpty());
        assertTrue(Argon2idCredentialVerifier.parse(
                LIBSODIUM_INTERACTIVE_HASH.replace("p=1", "p=99")).isEmpty());
        assertTrue(Argon2idCredentialVerifier.parse("x".repeat(513)).isEmpty());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
