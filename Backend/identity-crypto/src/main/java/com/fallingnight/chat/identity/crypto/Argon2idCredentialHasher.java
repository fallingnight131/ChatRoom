package com.fallingnight.chat.identity.crypto;

import com.fallingnight.chat.application.identity.CredentialHashPort;
import com.fallingnight.chat.application.identity.StoredCredential;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Creates libsodium-compatible Argon2id strings at the current V1 policy. */
public final class Argon2idCredentialHasher implements CredentialHashPort {
    private static final int MEMORY_KIB = 64 * 1024;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Supplier<byte[]> saltSupplier;

    public Argon2idCredentialHasher() {
        this(Argon2idCredentialHasher::randomSalt);
    }

    Argon2idCredentialHasher(Supplier<byte[]> saltSupplier) {
        this.saltSupplier = Objects.requireNonNull(saltSupplier, "saltSupplier");
    }

    @Override
    public StoredCredential.Argon2id hash(byte[] passwordUtf8) {
        Objects.requireNonNull(passwordUtf8, "passwordUtf8");
        byte[] salt = requireSalt(saltSupplier.get());
        byte[] output = new byte[HASH_BYTES];
        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        String encodedSalt = encoder.encodeToString(salt);
        try {
            Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(MEMORY_KIB)
                    .withIterations(ITERATIONS)
                    .withParallelism(PARALLELISM)
                    .withSalt(salt)
                    .build();
            try {
                Argon2BytesGenerator generator = new Argon2BytesGenerator();
                generator.init(parameters);
                generator.generateBytes(passwordUtf8, output);
            } finally {
                parameters.clear();
            }
            return new StoredCredential.Argon2id(
                    "$argon2id$v=19$m=" + MEMORY_KIB
                            + ",t=" + ITERATIONS
                            + ",p=" + PARALLELISM
                            + "$" + encodedSalt
                            + "$" + encoder.encodeToString(output));
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(output, (byte) 0);
        }
    }

    private static byte[] requireSalt(byte[] value) {
        Objects.requireNonNull(value, "salt");
        if (value.length != SALT_BYTES) {
            Arrays.fill(value, (byte) 0);
            throw new IllegalArgumentException("Argon2id salt must contain exactly 16 bytes");
        }
        return value;
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }
}
