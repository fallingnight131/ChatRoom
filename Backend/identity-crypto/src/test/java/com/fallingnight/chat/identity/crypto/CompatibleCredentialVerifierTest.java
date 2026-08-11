package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fallingnight.chat.application.identity.CredentialVerification;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompatibleCredentialVerifierTest {
    private final CompatibleCredentialVerifier verifier = new CompatibleCredentialVerifier();

    @Test
    void verifiesExactV1PasswordPlusSaltBytesAndRequiresUpgrade() {
        String password = "legacy-password-密码";
        String salt = "legacy-salt-1234";
        StoredCredential credential = new StoredCredential.LegacySha256(
                sha256Hex(password + salt), salt);

        assertEquals(CredentialVerification.VERIFIED_NEEDS_UPGRADE,
                verifier.verifyOrDummy(bytes(password), Optional.of(credential)));
        assertEquals(CredentialVerification.REJECTED,
                verifier.verifyOrDummy(bytes("wrong-password"), Optional.of(credential)));
    }

    @Test
    void malformedLegacyMaterialRejectsAfterArgonDummyWork() {
        StoredCredential malformed = new StoredCredential.LegacySha256("not-a-digest", "salt");
        assertEquals(CredentialVerification.REJECTED,
                verifier.verifyOrDummy(bytes("test-password"), Optional.of(malformed)));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes(value));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
