package com.fallingnight.chat.identity.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** RFC 8291 single-record aes128gcm content encoder. Returned bytes are caller-owned. */
public final class Rfc8291WebPushPayloadEncoder {
    public static final int MAX_PLAINTEXT_BYTES = 3_993;
    private static final int RECORD_SIZE = 4_096;
    private static final byte[] KEY_INFO_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    private final SecureRandom random;
    private final Supplier<KeyPair> keyPairs;

    public Rfc8291WebPushPayloadEncoder() {
        this(new SecureRandom(), Rfc8291WebPushPayloadEncoder::generateKeyPair);
    }

    Rfc8291WebPushPayloadEncoder(SecureRandom random, Supplier<KeyPair> keyPairs) {
        this.random = Objects.requireNonNull(random, "random");
        this.keyPairs = Objects.requireNonNull(keyPairs, "keyPairs");
    }

    public byte[] encode(byte[] plaintext, byte[] userAgentPublicKey, byte[] authSecret) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(userAgentPublicKey, "userAgentPublicKey");
        Objects.requireNonNull(authSecret, "authSecret");
        if (plaintext.length < 1 || plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("invalid Web Push plaintext length");
        }
        if (userAgentPublicKey.length != 65 || userAgentPublicKey[0] != 0x04) {
            throw new IllegalArgumentException("invalid Web Push P-256 public key");
        }
        if (authSecret.length != 16) throw new IllegalArgumentException("invalid Web Push auth secret");

        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] asPublic = null;
        byte[] shared = null;
        byte[] keyInfo = null;
        byte[] prkKey = null;
        byte[] ikm = null;
        byte[] prk = null;
        byte[] cek = null;
        byte[] nonce = null;
        byte[] record = null;
        try {
            KeyPair applicationServer = Objects.requireNonNull(keyPairs.get(), "applicationServerKeyPair");
            asPublic = encodePublic((ECPublicKey) applicationServer.getPublic());
            ECPublicKey userAgent = decodePublic(userAgentPublicKey,
                    ((ECPublicKey) applicationServer.getPublic()).getParams());
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(applicationServer.getPrivate());
            agreement.doPhase(userAgent, true);
            shared = agreement.generateSecret();
            keyInfo = concatenate(KEY_INFO_PREFIX, userAgentPublicKey, asPublic);
            prkKey = hmac(authSecret, shared);
            ikm = expand(prkKey, keyInfo, 32);
            prk = hmac(salt, ikm);
            cek = expand(prk, CEK_INFO, 16);
            nonce = expand(prk, NONCE_INFO, 12);
            record = Arrays.copyOf(plaintext, plaintext.length + 1);
            record[record.length - 1] = 0x02;

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(record);
            ByteArrayOutputStream output = new ByteArrayOutputStream(86 + ciphertext.length);
            output.writeBytes(salt);
            output.writeBytes(ByteBuffer.allocate(4).putInt(RECORD_SIZE).array());
            output.write(asPublic.length);
            output.writeBytes(asPublic);
            output.writeBytes(ciphertext);
            Arrays.fill(ciphertext, (byte) 0);
            return output.toByteArray();
        } catch (GeneralSecurityException exception) {
            throw new WebPushCredentialProtectionException("Web Push payload encryption failed", exception);
        } finally {
            clear(salt, asPublic, shared, keyInfo, prkKey, ikm, prk, cek, nonce, record);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new WebPushCredentialProtectionException("Web Push ephemeral key generation failed", exception);
        }
    }

    private static ECPublicKey decodePublic(byte[] encoded, ECParameterSpec parameters)
            throws GeneralSecurityException {
        ECPoint point = new ECPoint(
                new java.math.BigInteger(1, Arrays.copyOfRange(encoded, 1, 33)),
                new java.math.BigInteger(1, Arrays.copyOfRange(encoded, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(point, parameters));
    }

    private static byte[] encodePublic(ECPublicKey key) {
        byte[] result = new byte[65];
        result[0] = 0x04;
        writeCoordinate(key.getW().getAffineX().toByteArray(), result, 1);
        writeCoordinate(key.getW().getAffineY().toByteArray(), result, 33);
        return result;
    }

    private static void writeCoordinate(byte[] coordinate, byte[] target, int offset) {
        int source = Math.max(0, coordinate.length - 32);
        int length = coordinate.length - source;
        System.arraycopy(coordinate, source, target, offset + 32 - length, length);
        Arrays.fill(coordinate, (byte) 0);
    }

    private static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        byte[] input = Arrays.copyOf(info, info.length + 1);
        input[input.length - 1] = 0x01;
        byte[] expanded = hmac(prk, input);
        Arrays.fill(input, (byte) 0);
        byte[] result = Arrays.copyOf(expanded, length);
        Arrays.fill(expanded, (byte) 0);
        return result;
    }

    private static byte[] hmac(byte[] key, byte[] input) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(input);
    }

    private static byte[] concatenate(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        ByteBuffer output = ByteBuffer.allocate(length);
        for (byte[] value : values) output.put(value);
        return output.array();
    }

    private static void clear(byte[]... values) {
        for (byte[] value : values) if (value != null) Arrays.fill(value, (byte) 0);
    }
}
