package com.fallingnight.chat.application.notification;

import com.fallingnight.chat.application.security.SecretBytes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/** Session-bound, short-lived bearer/CSRF pair whose plaintext is owned and zeroable. */
public final class IssuedWebPushHttpCredential implements AutoCloseable {
    public static final int MIN_TOKEN_BYTES = 32;
    public static final int MAX_TOKEN_BYTES = 256;

    private final UUID sessionId;
    private final SecretBytes bearerToken;
    private final SecretBytes csrfToken;
    private final Instant expiresAt;

    private IssuedWebPushHttpCredential(
            UUID sessionId, SecretBytes bearerToken, SecretBytes csrfToken, Instant expiresAt) {
        this.sessionId = sessionId;
        this.bearerToken = bearerToken;
        this.csrfToken = csrfToken;
        this.expiresAt = expiresAt;
    }

    public static IssuedWebPushHttpCredential copyOf(
            UUID sessionId, byte[] bearerToken, byte[] csrfToken, Instant expiresAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        requireToken(bearerToken, "bearerToken");
        requireToken(csrfToken, "csrfToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(Instant.EPOCH)) {
            throw new IllegalArgumentException("expiresAt must be after the epoch");
        }
        return new IssuedWebPushHttpCredential(sessionId, SecretBytes.copyOf(bearerToken),
                SecretBytes.copyOf(csrfToken), expiresAt);
    }

    public UUID sessionId() { return sessionId; }
    public Instant expiresAt() { return expiresAt; }

    public <T> T withTokenCopies(BiFunction<byte[], byte[], T> action) {
        Objects.requireNonNull(action, "action");
        return bearerToken.withCopy(bearer -> csrfToken.withCopy(csrf -> action.apply(bearer, csrf)));
    }

    public boolean isClosed() { return bearerToken.isClosed() && csrfToken.isClosed(); }

    @Override public void close() { bearerToken.close(); csrfToken.close(); }

    @Override public String toString() {
        return "IssuedWebPushHttpCredential[sessionId=" + sessionId
                + ", expiresAt=" + expiresAt + ", tokens=REDACTED]";
    }

    private static void requireToken(byte[] token, String name) {
        Objects.requireNonNull(token, name);
        if (token.length < MIN_TOKEN_BYTES || token.length > MAX_TOKEN_BYTES) {
            throw new IllegalArgumentException(name + " length outside 32..256 bytes");
        }
        for (byte value : token) {
            int character = Byte.toUnsignedInt(value);
            if (!(character >= 'A' && character <= 'Z')
                    && !(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9')
                    && character != '-' && character != '_') {
                throw new IllegalArgumentException(name + " must be unpadded Base64URL ASCII");
            }
        }
    }
}
