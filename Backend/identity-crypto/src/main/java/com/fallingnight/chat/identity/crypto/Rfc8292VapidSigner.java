package com.fallingnight.chat.identity.crypto;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.AlgorithmParameters;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** RFC 8292 ES256 VAPID signer. The signing key is distinct from RFC 8291 ephemeral keys. */
public final class Rfc8292VapidSigner {
    public static final Duration MAX_TOKEN_LIFETIME = Duration.ofHours(24);
    private static final byte[] JWT_HEADER =
            "{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.US_ASCII);

    private final ECPrivateKey privateKey;
    private final byte[] encodedPublicKey;
    private final byte[] encodedSubject;
    private final Clock clock;
    private final Duration lifetime;

    public Rfc8292VapidSigner(KeyPair signingKey, URI subject, Clock clock, Duration lifetime) {
        Objects.requireNonNull(signingKey, "signingKey");
        if (!(signingKey.getPrivate() instanceof ECPrivateKey ecPrivate)
                || !(signingKey.getPublic() instanceof ECPublicKey ecPublic)
                || !isP256(ecPublic.getParams())) {
            throw new IllegalArgumentException("VAPID signing key must be P-256");
        }
        this.privateKey = ecPrivate;
        this.encodedPublicKey = encodePublic(ecPublic);
        this.encodedSubject = requireSubject(subject).getBytes(StandardCharsets.US_ASCII);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_TOKEN_LIFETIME) > 0) {
            throw new IllegalArgumentException("invalid VAPID token lifetime");
        }
    }

    public Rfc8292VapidAuthorization sign(URI pushEndpoint) {
        String audience = requireAudience(pushEndpoint);
        Instant now = clock.instant();
        long expiry;
        try { expiry = now.plus(lifetime).getEpochSecond(); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("invalid VAPID expiry", exception); }
        String payload = "{\"aud\":\"" + audience + "\",\"exp\":" + expiry
                + ",\"sub\":\"" + new String(encodedSubject, StandardCharsets.US_ASCII) + "\"}";
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        byte[] signingInput = (base64.encodeToString(JWT_HEADER) + "."
                + base64.encodeToString(payload.getBytes(StandardCharsets.US_ASCII)))
                .getBytes(StandardCharsets.US_ASCII);
        byte[] der = null;
        byte[] jose = null;
        byte[] authorization = null;
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(signingInput);
            der = signer.sign();
            jose = derToJose(der);
            String value = "vapid t=" + new String(signingInput, StandardCharsets.US_ASCII)
                    + "." + base64.encodeToString(jose)
                    + ",k=" + base64.encodeToString(encodedPublicKey);
            authorization = value.getBytes(StandardCharsets.US_ASCII);
            return new Rfc8292VapidAuthorization(authorization);
        } catch (GeneralSecurityException exception) {
            throw new WebPushCredentialProtectionException("VAPID signing failed", exception);
        } finally {
            Arrays.fill(signingInput, (byte) 0);
            if (der != null) Arrays.fill(der, (byte) 0);
            if (jose != null) Arrays.fill(jose, (byte) 0);
            if (authorization != null) Arrays.fill(authorization, (byte) 0);
        }
    }

    public byte[] publicKeyCopy() {
        return encodedPublicKey.clone();
    }

    private static String requireSubject(URI subject) {
        Objects.requireNonNull(subject, "subject");
        String value = subject.toASCIIString();
        if (value.length() < 1 || value.length() > 256 || value.indexOf('"') >= 0
                || value.indexOf('\\') >= 0 || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)
                || !("mailto".equals(subject.getScheme()) || "https".equals(subject.getScheme()))) {
            throw new IllegalArgumentException("invalid VAPID contact subject");
        }
        if ("https".equals(subject.getScheme()) && (subject.getHost() == null || subject.getUserInfo() != null)) {
            throw new IllegalArgumentException("invalid VAPID contact subject");
        }
        return value;
    }

    private static String requireAudience(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("invalid Web Push endpoint");
        }
        int port = endpoint.getPort();
        String host = endpoint.getHost().contains(":") ? "[" + endpoint.getHost() + "]" : endpoint.getHost();
        return "https://" + host + ((port == -1 || port == 443) ? "" : ":" + port);
    }

    private static byte[] encodePublic(ECPublicKey key) {
        byte[] result = new byte[65]; result[0] = 0x04;
        writeCoordinate(key.getW().getAffineX().toByteArray(), result, 1);
        writeCoordinate(key.getW().getAffineY().toByteArray(), result, 33);
        return result;
    }

    private static boolean isP256(ECParameterSpec candidate) {
        try {
            AlgorithmParameters algorithm = AlgorithmParameters.getInstance("EC");
            algorithm.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected = algorithm.getParameterSpec(ECParameterSpec.class);
            return candidate.getCurve().equals(expected.getCurve())
                    && candidate.getGenerator().equals(expected.getGenerator())
                    && candidate.getOrder().equals(expected.getOrder())
                    && candidate.getCofactor() == expected.getCofactor();
        } catch (GeneralSecurityException exception) {
            throw new WebPushCredentialProtectionException("P-256 parameters unavailable", exception);
        }
    }

    private static void writeCoordinate(byte[] source, byte[] target, int offset) {
        int start = Math.max(0, source.length - 32);
        int length = source.length - start;
        System.arraycopy(source, start, target, offset + 32 - length, length);
        Arrays.fill(source, (byte) 0);
    }

    private static byte[] derToJose(byte[] der) {
        if (der.length < 8 || der[0] != 0x30) throw new IllegalArgumentException("invalid ECDSA signature");
        int[] cursor = {1};
        int sequenceLength = readLength(der, cursor);
        if (sequenceLength != der.length - cursor[0] || der[cursor[0]++] != 0x02) {
            throw new IllegalArgumentException("invalid ECDSA signature");
        }
        byte[] r = readInteger(der, cursor);
        if (cursor[0] >= der.length || der[cursor[0]++] != 0x02) {
            throw new IllegalArgumentException("invalid ECDSA signature");
        }
        byte[] s = readInteger(der, cursor);
        if (cursor[0] != der.length) throw new IllegalArgumentException("invalid ECDSA signature");
        byte[] result = new byte[64];
        copyInteger(r, result, 0); copyInteger(s, result, 32);
        Arrays.fill(r, (byte) 0); Arrays.fill(s, (byte) 0);
        return result;
    }

    private static int readLength(byte[] value, int[] cursor) {
        if (cursor[0] >= value.length) throw new IllegalArgumentException("invalid ECDSA signature");
        int length = value[cursor[0]++] & 0xff;
        if (length < 128) return length;
        int bytes = length & 0x7f;
        if (bytes < 1 || bytes > 2 || cursor[0] + bytes > value.length) {
            throw new IllegalArgumentException("invalid ECDSA signature");
        }
        length = 0;
        while (bytes-- > 0) length = (length << 8) | (value[cursor[0]++] & 0xff);
        return length;
    }

    private static byte[] readInteger(byte[] value, int[] cursor) {
        int length = readLength(value, cursor);
        if (length < 1 || length > 33 || cursor[0] + length > value.length) {
            throw new IllegalArgumentException("invalid ECDSA signature");
        }
        byte[] result = Arrays.copyOfRange(value, cursor[0], cursor[0] + length);
        cursor[0] += length;
        return result;
    }

    private static void copyInteger(byte[] value, byte[] target, int offset) {
        int start = value.length == 33 && value[0] == 0 ? 1 : 0;
        int length = value.length - start;
        if (length > 32) throw new IllegalArgumentException("invalid ECDSA signature");
        System.arraycopy(value, start, target, offset + 32 - length, length);
    }
}
