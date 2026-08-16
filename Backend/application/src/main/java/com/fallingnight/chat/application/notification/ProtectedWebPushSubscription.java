package com.fallingnight.chat.application.notification;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owned encrypted subscription material plus a keyed, non-reversible endpoint lookup tag. */
public final class ProtectedWebPushSubscription implements AutoCloseable {
    public static final int MAX_ENDPOINT_CIPHERTEXT_BYTES = 4_096;
    public static final int MAX_P256DH_CIPHERTEXT_BYTES = 256;
    public static final int MAX_AUTH_CIPHERTEXT_BYTES = 128;
    public static final int ENDPOINT_LOOKUP_TAG_BYTES = 32;

    private final UUID accountId;
    private final UUID installationId;
    private final Optional<Instant> browserExpiresAt;
    private final String encryptionKeyId;
    private byte[] endpointCiphertext;
    private byte[] p256dhCiphertext;
    private byte[] authCiphertext;
    private byte[] endpointLookupTag;

    private ProtectedWebPushSubscription(
            UUID accountId,
            UUID installationId,
            Optional<Instant> browserExpiresAt,
            String encryptionKeyId,
            byte[] endpointCiphertext,
            byte[] p256dhCiphertext,
            byte[] authCiphertext,
            byte[] endpointLookupTag) {
        this.accountId = accountId;
        this.installationId = installationId;
        this.browserExpiresAt = browserExpiresAt;
        this.encryptionKeyId = encryptionKeyId;
        this.endpointCiphertext = endpointCiphertext;
        this.p256dhCiphertext = p256dhCiphertext;
        this.authCiphertext = authCiphertext;
        this.endpointLookupTag = endpointLookupTag;
    }

    public static ProtectedWebPushSubscription copyOf(
            UUID accountId,
            UUID installationId,
            Optional<Instant> browserExpiresAt,
            String encryptionKeyId,
            byte[] endpointCiphertext,
            byte[] p256dhCiphertext,
            byte[] authCiphertext,
            byte[] endpointLookupTag) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(installationId, "installationId");
        browserExpiresAt = Objects.requireNonNull(browserExpiresAt, "browserExpiresAt");
        browserExpiresAt.ifPresent(expiry -> {
            if (!expiry.isAfter(Instant.EPOCH)) {
                throw new IllegalArgumentException("browserExpiresAt must be after the epoch");
            }
        });
        Objects.requireNonNull(encryptionKeyId, "encryptionKeyId");
        requireCiphertext(endpointCiphertext, MAX_ENDPOINT_CIPHERTEXT_BYTES, "endpointCiphertext");
        requireCiphertext(p256dhCiphertext, MAX_P256DH_CIPHERTEXT_BYTES, "p256dhCiphertext");
        requireCiphertext(authCiphertext, MAX_AUTH_CIPHERTEXT_BYTES, "authCiphertext");
        Objects.requireNonNull(endpointLookupTag, "endpointLookupTag");
        if (!encryptionKeyId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("invalid encryptionKeyId");
        }
        if (endpointLookupTag.length != ENDPOINT_LOOKUP_TAG_BYTES) {
            throw new IllegalArgumentException("endpointLookupTag must contain 32 bytes");
        }
        return new ProtectedWebPushSubscription(
                accountId,
                installationId,
                browserExpiresAt,
                encryptionKeyId,
                endpointCiphertext.clone(),
                p256dhCiphertext.clone(),
                authCiphertext.clone(),
                endpointLookupTag.clone());
    }

    public UUID accountId() { return accountId; }

    public UUID installationId() { return installationId; }

    public Optional<Instant> browserExpiresAt() { return browserExpiresAt; }

    public String encryptionKeyId() { return encryptionKeyId; }

    public synchronized <T, E extends Exception> T withCopies(
            ProtectedBytesFunction<T, E> action) throws E {
        Objects.requireNonNull(action, "action");
        requireOpen();
        byte[] endpointCopy = endpointCiphertext.clone();
        byte[] p256dhCopy = p256dhCiphertext.clone();
        byte[] authCopy = authCiphertext.clone();
        byte[] tagCopy = endpointLookupTag.clone();
        try {
            return action.apply(endpointCopy, p256dhCopy, authCopy, tagCopy);
        } finally {
            Arrays.fill(endpointCopy, (byte) 0);
            Arrays.fill(p256dhCopy, (byte) 0);
            Arrays.fill(authCopy, (byte) 0);
            Arrays.fill(tagCopy, (byte) 0);
        }
    }

    public synchronized boolean isClosed() { return endpointCiphertext == null; }

    @Override
    public synchronized void close() {
        clear(endpointCiphertext);
        clear(p256dhCiphertext);
        clear(authCiphertext);
        clear(endpointLookupTag);
        endpointCiphertext = null;
        p256dhCiphertext = null;
        authCiphertext = null;
        endpointLookupTag = null;
    }

    @Override
    public String toString() {
        return "ProtectedWebPushSubscription[accountId=" + accountId
                + ", installationId=" + installationId
                + ", browserExpiresAt=" + browserExpiresAt
                + ", encryptionKeyId=" + encryptionKeyId
                + ", protectedBytes=REDACTED]";
    }

    private synchronized void requireOpen() {
        if (endpointCiphertext == null) {
            throw new IllegalStateException("protected subscription is closed");
        }
    }

    private static void requireCiphertext(byte[] value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.length < 17 || value.length > maximum) {
            throw new IllegalArgumentException(name + " length is invalid");
        }
    }

    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    @FunctionalInterface
    public interface ProtectedBytesFunction<T, E extends Exception> {
        T apply(byte[] endpointCiphertext, byte[] p256dhCiphertext,
                byte[] authCiphertext, byte[] endpointLookupTag) throws E;
    }
}
