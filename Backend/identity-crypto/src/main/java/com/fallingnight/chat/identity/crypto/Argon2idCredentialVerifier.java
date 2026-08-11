package com.fallingnight.chat.identity.crypto;

import com.fallingnight.chat.application.identity.CredentialVerifierPort;
import com.fallingnight.chat.application.identity.CredentialVerification;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Verifies bounded libsodium-compatible Argon2id encoded strings. */
public final class Argon2idCredentialVerifier implements CredentialVerifierPort {
    private static final int VERSION_13 = 19;
    private static final int MAX_ENCODED_BYTES = 512;
    private static final int MAX_MEMORY_KIB = 64 * 1024;
    private static final int MAX_ITERATIONS = 4;
    private static final int MAX_PARALLELISM = 4;
    private static final Pattern PARAMETERS = Pattern.compile("m=(\\d+),t=(\\d+),p=(\\d+)");
    private static final String DUMMY_HASH =
            "$argon2id$v=19$m=65536,t=2,p=1$E1wX9i9QVyERI3DZqWy0Kg$"
                    + "nDO9/91zFAJGLsvBZudV4nKX4eGGHWTwuimwcjPzPcw";
    private static final ParsedHash DUMMY_PARSED = parse(DUMMY_HASH).orElseThrow();

    @Override
    public CredentialVerification verifyOrDummy(
            byte[] passwordUtf8, Optional<StoredCredential> storedCredential) {
        Objects.requireNonNull(passwordUtf8, "passwordUtf8");
        Objects.requireNonNull(storedCredential, "storedCredential");
        Optional<ParsedHash> parsedStored = storedCredential
                .filter(StoredCredential.Argon2id.class::isInstance)
                .map(StoredCredential.Argon2id.class::cast)
                .map(StoredCredential.Argon2id::encodedHash)
                .flatMap(Argon2idCredentialVerifier::parse);
        ParsedHash selected = parsedStored.orElse(DUMMY_PARSED);
        byte[] actual = new byte[selected.expected().length];
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(selected.memoryKiB())
                    .withIterations(selected.iterations())
                    .withParallelism(selected.parallelism())
                    .withSalt(selected.salt())
                    .build();
            try {
                Argon2BytesGenerator generator = new Argon2BytesGenerator();
                generator.init(parameters);
                generator.generateBytes(passwordUtf8, actual);
            } finally {
                parameters.clear();
            }
            return parsedStored.isPresent()
                    && MessageDigest.isEqual(actual, selected.expected())
                            ? CredentialVerification.VERIFIED
                            : CredentialVerification.REJECTED;
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    void performDummy(byte[] passwordUtf8) {
        verifyOrDummy(passwordUtf8, Optional.empty());
    }

    static Optional<ParsedHash> parse(String encoded) {
        if (encoded == null || encoded.length() == 0
                || encoded.length() > MAX_ENCODED_BYTES) {
            return Optional.empty();
        }
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 6
                || !parts[0].isEmpty()
                || !"argon2id".equals(parts[1])
                || !parts[2].startsWith("v=")) {
            return Optional.empty();
        }
        final int version;
        try {
            version = Integer.parseInt(parts[2].substring(2));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        if (version != VERSION_13) {
            return Optional.empty();
        }
        Matcher matcher = PARAMETERS.matcher(parts[3]);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        final int memory;
        final int iterations;
        final int parallelism;
        try {
            memory = Integer.parseInt(matcher.group(1));
            iterations = Integer.parseInt(matcher.group(2));
            parallelism = Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        if (parallelism < 1 || parallelism > MAX_PARALLELISM
                || iterations < 1 || iterations > MAX_ITERATIONS
                || memory < 8 * parallelism || memory > MAX_MEMORY_KIB) {
            return Optional.empty();
        }
        try {
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);
            if (salt.length < 16 || salt.length > 64
                    || expected.length < 16 || expected.length > 64) {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(expected, (byte) 0);
                return Optional.empty();
            }
            return Optional.of(new ParsedHash(memory, iterations, parallelism, salt, expected));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    record ParsedHash(
            int memoryKiB,
            int iterations,
            int parallelism,
            byte[] salt,
            byte[] expected) {
        ParsedHash {
            salt = salt.clone();
            expected = expected.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] expected() {
            return expected.clone();
        }
    }
}
