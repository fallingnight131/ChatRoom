package com.fallingnight.chat.identity.crypto;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomPasswordEncoding;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomPasswordHashPort;
import com.fallingnight.chat.application.identity.CredentialHashPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Argon2id verification hash plus domain-separated keyed V1 room retry tag. */
public final class LegacyV1RoomPasswordEncoder
        implements LegacyV1RoomPasswordHashPort, AutoCloseable {
    public static final int KEY_BYTES = 32;
    private static final byte[] DOMAIN =
            "chat-room:v1-room-password-idempotency:v1\0".getBytes(StandardCharsets.US_ASCII);
    private final CredentialHashPort slowHasher;
    private final byte[] domain;
    private byte[] key;

    public LegacyV1RoomPasswordEncoder(byte[] key) {
        this(key, new Argon2idCredentialHasher(), DOMAIN);
    }

    LegacyV1RoomPasswordEncoder(byte[] key, CredentialHashPort slowHasher, byte[] domain) {
        Objects.requireNonNull(key, "key");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("room password HMAC key must contain 32 bytes");
        }
        this.key = key.clone();
        this.slowHasher = Objects.requireNonNull(slowHasher, "slowHasher");
        this.domain = Objects.requireNonNull(domain, "domain").clone();
        if (this.domain.length == 0) throw new IllegalArgumentException("HMAC domain is empty");
    }

    @Override public synchronized LegacyV1RoomPasswordEncoding hash(byte[] passwordUtf8) {
        Objects.requireNonNull(passwordUtf8, "passwordUtf8");
        if (key == null) throw new IllegalStateException("room password encoder is closed");
        String encodedHash = slowHasher.hash(passwordUtf8).encodedHash();
        byte[] tag = hmac(passwordUtf8);
        try {
            return new LegacyV1RoomPasswordEncoding(encodedHash,
                    "hmac-sha256:v1:" + Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(tag));
        } finally { Arrays.fill(tag, (byte) 0); }
    }

    private byte[] hmac(byte[] passwordUtf8) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update(domain);
            return hmac.doFinal(passwordUtf8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    public synchronized boolean isClosed() { return key == null; }
    @Override public synchronized void close() {
        if (key != null) { Arrays.fill(key, (byte) 0); key = null; }
        Arrays.fill(domain, (byte) 0);
    }
}
