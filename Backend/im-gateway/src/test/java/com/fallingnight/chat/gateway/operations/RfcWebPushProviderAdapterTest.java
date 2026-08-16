package com.fallingnight.chat.gateway.operations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.notification.WebPushProviderCommand;
import com.fallingnight.chat.application.notification.WebPushProviderResult;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import com.fallingnight.chat.identity.crypto.Rfc8291WebPushPayloadEncoder;
import com.fallingnight.chat.identity.crypto.Rfc8292VapidSigner;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RfcWebPushProviderAdapterTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);

    @Test
    void postsOwnedEncryptedIdentityOnlyPayloadAndClearsTransportArrays() throws Exception {
        byte[][] observed = new byte[2][];
        long[] ttl = new long[1];
        Rfc8292VapidSigner signer = signer();
        RfcWebPushProviderAdapter adapter = new RfcWebPushProviderAdapter(
                new Rfc8291WebPushPayloadEncoder(), signer::sign,
                providerPolicy(),
                (endpoint, authorization, body, value) -> {
                    assertEquals(URI.create("https://push.example/opaque"), endpoint);
                    assertTrue(new String(authorization, StandardCharsets.US_ASCII).startsWith("vapid t="));
                    assertTrue(body.length > 86);
                    observed[0] = authorization; observed[1] = body; ttl[0] = value;
                    return 201;
                }, fixedClock());
        try (WebPushSubscriptionRegistration registration = registration()) {
            assertEquals(WebPushProviderResult.DELIVERED, adapter.deliver(command(registration)));
        }
        assertEquals(600, ttl[0]);
        assertArrayEquals(new byte[observed[0].length], observed[0]);
        assertArrayEquals(new byte[observed[1].length], observed[1]);
        signer.close();
    }

    @Test
    void mapsOnlyFixedProviderOutcomesAndContainsTransportFailure() throws Exception {
        int[] statuses = {202, 404, 410, 401, 403, 400, 429, 500};
        WebPushProviderResult[] expected = {
            WebPushProviderResult.DELIVERED,
            WebPushProviderResult.INVALID_SUBSCRIPTION, WebPushProviderResult.INVALID_SUBSCRIPTION,
            WebPushProviderResult.AUTHENTICATION_FAILURE, WebPushProviderResult.AUTHENTICATION_FAILURE,
            WebPushProviderResult.TRANSIENT_FAILURE, WebPushProviderResult.TRANSIENT_FAILURE,
            WebPushProviderResult.TRANSIENT_FAILURE,
        };
        for (int index = 0; index < statuses.length; index++) {
            int status = statuses[index];
            try (Rfc8292VapidSigner signer = signer();
                 WebPushSubscriptionRegistration registration = registration()) {
                RfcWebPushProviderAdapter adapter = new RfcWebPushProviderAdapter(
                        new Rfc8291WebPushPayloadEncoder(), signer::sign,
                        providerPolicy(),
                        (endpoint, auth, body, ttl) -> status, fixedClock());
                assertEquals(expected[index], adapter.deliver(command(registration)));
            }
        }
        try (Rfc8292VapidSigner signer = signer();
             WebPushSubscriptionRegistration registration = registration()) {
            RfcWebPushProviderAdapter adapter = new RfcWebPushProviderAdapter(
                    new Rfc8291WebPushPayloadEncoder(), signer::sign,
                    providerPolicy(),
                    (endpoint, auth, body, ttl) -> { throw new java.io.IOException("private provider detail"); },
                    fixedClock());
            assertEquals(WebPushProviderResult.TRANSIENT_FAILURE, adapter.deliver(command(registration)));
        }
    }

    @Test
    void rejectsExpiredSubscriptionWithoutTransport() throws Exception {
        int[] calls = {0};
        try (Rfc8292VapidSigner signer = signer()) {
            RfcWebPushProviderAdapter adapter = new RfcWebPushProviderAdapter(
                    new Rfc8291WebPushPayloadEncoder(), signer::sign,
                    providerPolicy(),
                    (endpoint, auth, body, ttl) -> { calls[0]++; return 201; }, fixedClock());
            try (WebPushSubscriptionRegistration registration = registration()) {
                WebPushProviderCommand expired = new WebPushProviderCommand(registration,
                        uuid(3), uuid(2), uuid(3), false, NOW);
                assertEquals(WebPushProviderResult.TRANSIENT_FAILURE, adapter.deliver(expired));
            }
        }
        assertEquals(0, calls[0]);
    }

    @Test
    void capsProviderTtlAndContainsAuthorizationFailure() throws Exception {
        long[] observedTtl = {0};
        try (Rfc8292VapidSigner signer = signer();
             WebPushSubscriptionRegistration registration = registration()) {
            RfcWebPushProviderAdapter adapter = new RfcWebPushProviderAdapter(
                    new Rfc8291WebPushPayloadEncoder(), signer::sign, providerPolicy(),
                    (endpoint, auth, body, ttl) -> {
                        observedTtl[0] = ttl;
                        return 201;
                    }, fixedClock());
            WebPushProviderCommand longLived = new WebPushProviderCommand(registration,
                    uuid(3), uuid(2), uuid(3), false, NOW.plus(Duration.ofDays(2)));
            assertEquals(WebPushProviderResult.DELIVERED, adapter.deliver(longLived));
        }
        assertEquals(Duration.ofHours(24).toSeconds(), observedTtl[0]);

        try (WebPushSubscriptionRegistration registration = registration()) {
            RfcWebPushProviderAdapter adapter = new RfcWebPushProviderAdapter(
                    new Rfc8291WebPushPayloadEncoder(), endpoint -> {
                        throw new IllegalStateException("private signer detail");
                    }, providerPolicy(), (endpoint, auth, body, ttl) -> 201, fixedClock());
            assertEquals(WebPushProviderResult.AUTHENTICATION_FAILURE,
                    adapter.deliver(command(registration)));
        }
    }

    @Test
    void rejectsProviderOriginsOutsideTheExactAllowlistBeforeCryptoOrTransport() throws Exception {
        int[] authorizationCalls = {0};
        int[] transportCalls = {0};
        try (Rfc8292VapidSigner signer = signer();
             WebPushSubscriptionRegistration registration = registration("https://push.example.attacker.test/opaque")) {
            RfcWebPushProviderAdapter adapter = new RfcWebPushProviderAdapter(
                    new Rfc8291WebPushPayloadEncoder(), endpoint -> {
                        authorizationCalls[0]++;
                        return signer.sign(endpoint);
                    }, providerPolicy(), (endpoint, auth, body, ttl) -> {
                        transportCalls[0]++;
                        return 201;
                    }, fixedClock());
            assertEquals(WebPushProviderResult.INVALID_SUBSCRIPTION,
                    adapter.deliver(command(registration)));
        }
        assertEquals(0, authorizationCalls[0]);
        assertEquals(0, transportCalls[0]);
    }

    @Test
    void validatesCanonicalExactProviderOrigins() {
        ExactWebPushProviderOriginPolicy policy = providerPolicy();
        assertTrue(policy.test(URI.create("https://push.example/path?q=opaque")));
        assertFalse(policy.test(URI.create("https://push.example.attacker.test/path")));
        assertFalse(policy.test(URI.create("https://push.example:8443/path")));
        assertThrows(IllegalArgumentException.class,
                () -> new ExactWebPushProviderOriginPolicy(Set.of("https://push.example/")));
        assertThrows(IllegalArgumentException.class,
                () -> new ExactWebPushProviderOriginPolicy(Set.of("http://push.example")));
    }

    private static WebPushProviderCommand command(WebPushSubscriptionRegistration registration) {
        return new WebPushProviderCommand(registration, uuid(3), uuid(2), uuid(3), true,
                NOW.plusSeconds(600));
    }

    private static WebPushSubscriptionRegistration registration() throws Exception {
        return registration("https://push.example/opaque");
    }

    private static WebPushSubscriptionRegistration registration(String endpoint) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        java.security.interfaces.ECPublicKey publicKey =
                (java.security.interfaces.ECPublicKey) generator.generateKeyPair().getPublic();
        byte[] encoded = new byte[65]; encoded[0] = 0x04;
        coordinate(publicKey.getW().getAffineX().toByteArray(), encoded, 1);
        coordinate(publicKey.getW().getAffineY().toByteArray(), encoded, 33);
        return WebPushSubscriptionRegistration.copyOf(uuid(1), uuid(4), Optional.empty(),
                endpoint.getBytes(StandardCharsets.UTF_8), encoded, new byte[16]);
    }

    private static ExactWebPushProviderOriginPolicy providerPolicy() {
        return new ExactWebPushProviderOriginPolicy(Set.of("https://push.example"));
    }

    private static Rfc8292VapidSigner signer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return new Rfc8292VapidSigner(generator.generateKeyPair(), URI.create("mailto:push@example.com"),
                fixedClock(), Duration.ofMinutes(10));
    }

    private static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    private static UUID uuid(int value) { return UUID.fromString("00000000-0000-4000-8000-00000000000" + value); }
    private static void coordinate(byte[] source, byte[] target, int offset) {
        int start = Math.max(0, source.length - 32); int length = source.length - start;
        System.arraycopy(source, start, target, offset + 32 - length, length);
    }
}
