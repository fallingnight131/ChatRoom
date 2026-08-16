package com.fallingnight.chat.identity.crypto;

import com.fallingnight.chat.application.notification.ProtectedWebPushSubscription;
import com.fallingnight.chat.application.notification.WebPushCredentialProtectionPort;
import com.fallingnight.chat.application.notification.WebPushCredentialUnprotectionPort;
import com.fallingnight.chat.application.notification.WebPushKeyCustodyPort;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM credentials plus a separately keyed HMAC-SHA256 endpoint lookup tag. */
public final class AesGcmWebPushCredentialProtector
        implements WebPushCredentialProtectionPort, WebPushCredentialUnprotectionPort {
    static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte FORMAT_VERSION = 1;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WebPushKeyCustodyPort keyCustody;
    private final Supplier<byte[]> nonceSupplier;

    public AesGcmWebPushCredentialProtector(WebPushKeyCustodyPort keyCustody) {
        this(keyCustody, AesGcmWebPushCredentialProtector::randomNonce);
    }

    AesGcmWebPushCredentialProtector(
            WebPushKeyCustodyPort keyCustody, Supplier<byte[]> nonceSupplier) {
        this.keyCustody = Objects.requireNonNull(keyCustody, "keyCustody");
        this.nonceSupplier = Objects.requireNonNull(nonceSupplier, "nonceSupplier");
    }

    @Override
    public ProtectedWebPushSubscription protect(WebPushSubscriptionRegistration registration) {
        Objects.requireNonNull(registration, "registration");
        return registration.withEndpointCopy(endpoint -> registration.withP256dhCopy(p256dh ->
                registration.withAuthSecretCopy(auth -> keyCustody.withActiveEncryptionKey(
                        (keyId, key) -> protect(registration, keyId, key,
                                endpoint, p256dh, auth)))));
    }

    @Override
    public WebPushSubscriptionRegistration unprotect(
            ProtectedWebPushSubscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        try {
            return subscription.withCopies((endpoint, p256dh, auth, lookupTag) ->
                    keyCustody.withEncryptionKey(subscription.encryptionKeyId(), key -> {
                        byte[] plainEndpoint = null;
                        byte[] plainP256dh = null;
                        byte[] plainAuth = null;
                        byte[] expectedTag = null;
                        try {
                            requireKey(key);
                            plainEndpoint = decrypt(subscription, Purpose.ENDPOINT, key, endpoint);
                            plainP256dh = decrypt(subscription, Purpose.P256DH, key, p256dh);
                            plainAuth = decrypt(subscription, Purpose.AUTH, key, auth);
                            byte[] endpointForTag = plainEndpoint;
                            expectedTag = keyCustody.withEndpointLookupKey(
                                    lookupKey -> endpointLookupTag(lookupKey, endpointForTag));
                            if (!MessageDigest.isEqual(expectedTag, lookupTag)) {
                                throw new WebPushCredentialProtectionException(
                                        "Web Push endpoint lookup authentication failed", null);
                            }
                            return WebPushSubscriptionRegistration.copyOf(
                                    subscription.accountId(), subscription.installationId(),
                                    subscription.browserExpiresAt(), plainEndpoint,
                                    plainP256dh, plainAuth);
                        } finally {
                            clear(plainEndpoint);
                            clear(plainP256dh);
                            clear(plainAuth);
                            clear(expectedTag);
                        }
                    }));
        } catch (WebPushCredentialProtectionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WebPushCredentialProtectionException(
                    "Web Push credential unprotection failed", exception);
        }
    }

    private ProtectedWebPushSubscription protect(
            WebPushSubscriptionRegistration registration,
            String keyId,
            byte[] key,
            byte[] endpoint,
            byte[] p256dh,
            byte[] auth) {
        Objects.requireNonNull(keyId, "keyId");
        requireKey(key);
        byte[] protectedEndpoint = null;
        byte[] protectedP256dh = null;
        byte[] protectedAuth = null;
        byte[] lookupTag = null;
        try {
            protectedEndpoint = encrypt(registration, Purpose.ENDPOINT, keyId, key, endpoint);
            protectedP256dh = encrypt(registration, Purpose.P256DH, keyId, key, p256dh);
            protectedAuth = encrypt(registration, Purpose.AUTH, keyId, key, auth);
            lookupTag = keyCustody.withEndpointLookupKey(
                    lookupKey -> endpointLookupTag(lookupKey, endpoint));
            return ProtectedWebPushSubscription.copyOf(
                    registration.accountId(), registration.installationId(),
                    registration.browserExpiresAt(), keyId, protectedEndpoint,
                    protectedP256dh, protectedAuth, lookupTag);
        } finally {
            clear(protectedEndpoint);
            clear(protectedP256dh);
            clear(protectedAuth);
            clear(lookupTag);
        }
    }

    private byte[] encrypt(
            WebPushSubscriptionRegistration registration,
            Purpose purpose,
            String keyId,
            byte[] key,
            byte[] plaintext) {
        byte[] nonce = Objects.requireNonNull(nonceSupplier.get(), "nonce");
        if (nonce.length != NONCE_BYTES) {
            clear(nonce);
            throw new IllegalArgumentException("AES-GCM nonce must contain exactly 12 bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(registration.accountId().toString(),
                    registration.installationId().toString(), purpose, keyId));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] encoded = new byte[1 + NONCE_BYTES + encrypted.length];
            encoded[0] = FORMAT_VERSION;
            System.arraycopy(nonce, 0, encoded, 1, NONCE_BYTES);
            System.arraycopy(encrypted, 0, encoded, 1 + NONCE_BYTES, encrypted.length);
            clear(encrypted);
            return encoded;
        } catch (GeneralSecurityException exception) {
            throw new WebPushCredentialProtectionException(
                    "Web Push credential protection failed", exception);
        } finally {
            clear(nonce);
        }
    }

    private static byte[] decrypt(
            ProtectedWebPushSubscription subscription,
            Purpose purpose,
            byte[] key,
            byte[] encoded) {
        if (encoded.length < 1 + NONCE_BYTES + 16 || encoded[0] != FORMAT_VERSION) {
            throw new WebPushCredentialProtectionException(
                    "unsupported Web Push credential ciphertext", null);
        }
        byte[] nonce = Arrays.copyOfRange(encoded, 1, 1 + NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(encoded, 1 + NONCE_BYTES, encoded.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(subscription.accountId().toString(),
                    subscription.installationId().toString(), purpose,
                    subscription.encryptionKeyId()));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new WebPushCredentialProtectionException(
                    "Web Push credential authentication failed", exception);
        } finally {
            clear(nonce);
            clear(ciphertext);
        }
    }

    private static byte[] endpointLookupTag(byte[] key, byte[] endpoint) {
        requireKey(key);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(endpoint);
        } catch (GeneralSecurityException exception) {
            throw new WebPushCredentialProtectionException(
                    "Web Push endpoint lookup protection failed", exception);
        }
    }

    private static byte[] aad(
            String accountId, String installationId, Purpose purpose, String keyId) {
        return ("chat-room:web-push:v1\0" + purpose.name() + "\0" + accountId
                + "\0" + installationId + "\0" + keyId).getBytes(StandardCharsets.US_ASCII);
    }

    private static void requireKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("Web Push keys must contain exactly 32 bytes");
        }
    }

    private static byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private enum Purpose { ENDPOINT, P256DH, AUTH }
}
