package com.fallingnight.chat.identity.crypto;

import com.fallingnight.chat.application.identity.CredentialVerifierPort;
import com.fallingnight.chat.application.identity.CredentialVerification;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Verifies modern Argon2id and temporary V1 salted-SHA credentials. */
public final class CompatibleCredentialVerifier implements CredentialVerifierPort {
    private static final int SHA256_BYTES = 32;
    private static final int MAX_LEGACY_SALT_BYTES = 512;
    private final Argon2idCredentialVerifier argon2id = new Argon2idCredentialVerifier();

    @Override
    public CredentialVerification verifyOrDummy(
            byte[] passwordUtf8, Optional<StoredCredential> storedCredential) {
        Objects.requireNonNull(passwordUtf8, "passwordUtf8");
        Objects.requireNonNull(storedCredential, "storedCredential");
        if (storedCredential.orElse(null) instanceof StoredCredential.LegacySha256 legacy) {
            boolean matches = verifyLegacy(passwordUtf8, legacy);
            argon2id.performDummy(passwordUtf8);
            return matches
                    ? CredentialVerification.VERIFIED_NEEDS_UPGRADE
                    : CredentialVerification.REJECTED;
        }
        return argon2id.verifyOrDummy(passwordUtf8, storedCredential);
    }

    private static boolean verifyLegacy(
            byte[] passwordUtf8, StoredCredential.LegacySha256 credential) {
        byte[] salt = credential.salt().getBytes(StandardCharsets.UTF_8);
        byte[] expected = decodeDigest(credential.hexDigest());
        if (salt.length > MAX_LEGACY_SALT_BYTES || expected.length != SHA256_BYTES) {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            return false;
        }
        byte[] material = Arrays.copyOf(passwordUtf8, passwordUtf8.length + salt.length);
        System.arraycopy(salt, 0, material, passwordUtf8.length, salt.length);
        byte[] actual = sha256(material);
        try {
            return MessageDigest.isEqual(actual, expected);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(material, (byte) 0);
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] decodeDigest(String value) {
        if (value.length() != SHA256_BYTES * 2) {
            return new byte[0];
        }
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
