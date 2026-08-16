package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class WebPushHttpBoundaryTest {
    @Test
    void isDefaultOffAndAllowsOnlyOneExactCanonicalHttpsOrigin() {
        assertFalse(WebPushHttpApiPolicy.DISABLED.enabled());
        assertFalse(WebPushHttpApiPolicy.DISABLED.allows(List.of("https://chat.example")));
        WebPushHttpApiPolicy policy = WebPushHttpApiPolicy.enabled(Set.of(
                "https://chat.example", "https://web.example:8443"));
        assertTrue(policy.allows(List.of("https://chat.example")));
        assertTrue(policy.allows(List.of("https://web.example:8443")));
        assertFalse(policy.allows(List.of()));
        assertFalse(policy.allows(List.of(
                "https://chat.example", "https://chat.example")));
        assertFalse(policy.allows(List.of("https://CHAT.example")));
        assertThrows(IllegalArgumentException.class,
                () -> WebPushHttpApiPolicy.enabled(Set.of("http://chat.example")));
        assertThrows(IllegalArgumentException.class,
                () -> WebPushHttpApiPolicy.enabled(Set.of("https://chat.example/")));
    }

    @Test
    void decodesOnlyTheStrictBrowserSubsetAndClearsTransportBytes() {
        byte[] p256dh = new byte[65]; p256dh[0] = 0x04;
        byte[] auth = new byte[16]; Arrays.fill(auth, (byte) 7);
        String json = "{\"endpoint\":\"https://push.example/sub/opaque\","
                + "\"expirationTime\":1767225600000,\"keys\":{\"p256dh\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(p256dh)
                + "\",\"auth\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(auth) + "\"}}";
        byte[] wire = json.getBytes(StandardCharsets.UTF_8);
        UUID installation = UUID.randomUUID();

        var request = new WebPushSubscriptionJsonCodec().decode(installation, wire);

        assertEquals(installation, request.installationId());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"),
                request.browserExpiresAt().orElseThrow());
        assertTrue(allZero(wire));
        request.close();
        assertTrue(request.isClosed());
    }

    @Test
    void rejectsUnknownDuplicateMalformedAndOversizedInputWhileClearingIt() {
        WebPushSubscriptionJsonCodec codec = new WebPushSubscriptionJsonCodec();
        for (String json : List.of(
                "{}",
                "{\"endpoint\":\"https://push.example/a\",\"endpoint\":\"https://push.example/b\"}",
                "{\"unknown\":true}",
                "{\"endpoint\":\"https://push.example/é\"}")) {
            byte[] wire = json.getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decode(UUID.randomUUID(), wire));
            assertTrue(allZero(wire));
        }
        byte[] oversized = new byte[WebPushSubscriptionJsonCodec.MAX_WIRE_BYTES + 1];
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(UUID.randomUUID(), oversized));
        assertTrue(allZero(oversized));
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) if (item != 0) return false;
        return true;
    }
}
