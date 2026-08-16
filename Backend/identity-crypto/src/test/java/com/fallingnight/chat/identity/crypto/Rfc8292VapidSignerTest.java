package com.fallingnight.chat.identity.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;

final class Rfc8292VapidSignerTest {
    @Test
    void bindsEs256AuthorizationToExactProviderOriginAndContact() throws Exception {
        KeyPair keys = keys();
        Rfc8292VapidSigner signer = new Rfc8292VapidSigner(keys,
                URI.create("mailto:push@example.com"),
                Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC),
                Duration.ofMinutes(10));
        byte[] ascii;
        try (Rfc8292VapidAuthorization authorization =
                     signer.sign(URI.create("https://push.example.net:443/path/opaque?x=1"))) {
            ascii = authorization.withAsciiCopy(value -> value.clone());
            assertEquals("Rfc8292VapidAuthorization[value=REDACTED]", authorization.toString());
            assertFalse(authorization.isClosed());
        }
        String header = new String(ascii, StandardCharsets.US_ASCII);
        assertTrue(header.startsWith("vapid t="));
        String[] parameters = header.substring("vapid ".length()).split(",k=", 2);
        String token = parameters[0].substring(2);
        String[] parts = token.split("\\.");
        assertEquals("{\"typ\":\"JWT\",\"alg\":\"ES256\"}", decodeText(parts[0]));
        assertEquals("{\"aud\":\"https://push.example.net\",\"exp\":1800000600,"
                + "\"sub\":\"mailto:push@example.com\"}", decodeText(parts[1]));
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(signer.publicKeyCopy()),
                parameters[1]);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(joseToDer(Base64.getUrlDecoder().decode(parts[2]))));
        java.util.Arrays.fill(ascii, (byte) 0);
    }

    @Test
    void rejectsUnsafeEndpointSubjectAndLifetimeAndRefusesClosedAuthorization() throws Exception {
        KeyPair keys = keys();
        Clock clock = Clock.systemUTC();
        assertThrows(IllegalArgumentException.class, () -> new Rfc8292VapidSigner(
                keys, URI.create("http://contact.example"), clock, Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class, () -> new Rfc8292VapidSigner(
                keys, URI.create("mailto:push@example.com"), clock, Duration.ofHours(25)));
        Rfc8292VapidSigner signer = new Rfc8292VapidSigner(keys,
                URI.create("https://contact.example/push"), clock, Duration.ofMinutes(10));
        assertThrows(IllegalArgumentException.class,
                () -> signer.sign(URI.create("http://push.example/path")));
        Rfc8292VapidAuthorization authorization = signer.sign(URI.create("https://push.example/path"));
        authorization.close();
        assertTrue(authorization.isClosed());
        assertThrows(IllegalStateException.class, () -> authorization.withAsciiCopy(value -> null));
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String decodeText(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
    }

    private static byte[] joseToDer(byte[] jose) throws Exception {
        if (jose.length != 64) throw new IllegalArgumentException("invalid JOSE signature");
        byte[] r = unsignedInteger(java.util.Arrays.copyOfRange(jose, 0, 32));
        byte[] s = unsignedInteger(java.util.Arrays.copyOfRange(jose, 32, 64));
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(0x02); body.write(r.length); body.writeBytes(r);
        body.write(0x02); body.write(s.length); body.writeBytes(s);
        byte[] encoded = body.toByteArray();
        java.io.ByteArrayOutputStream sequence = new java.io.ByteArrayOutputStream();
        sequence.write(0x30); sequence.write(encoded.length); sequence.writeBytes(encoded);
        return sequence.toByteArray();
    }

    private static byte[] unsignedInteger(byte[] value) {
        int first = 0;
        while (first < value.length - 1 && value[first] == 0) first++;
        int length = value.length - first;
        byte[] result = new byte[length + ((value[first] & 0x80) == 0 ? 0 : 1)];
        System.arraycopy(value, first, result, result.length - length, length);
        return result;
    }
}
