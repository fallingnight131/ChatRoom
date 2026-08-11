package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedProxyPolicyTest {
    @Test
    void directModeIgnoresUntrustedForwardingClaims() throws Exception {
        TrustedProxyPolicy policy = TrustedProxyPolicy.directOnly();

        PeerResolution direct = policy.resolve(peer("192.0.2.10"), List.of());
        assertTrue(direct.accepted());
        assertEquals("192.0.2.10", direct.clientAddress());
        assertEquals(PeerResolutionDecision.DIRECT, direct.decision());

        PeerResolution spoofed = policy.resolve(
                peer("192.0.2.10"), List.of("203.0.113.99"));
        assertEquals("192.0.2.10", spoofed.clientAddress());
        assertEquals(
                PeerResolutionDecision.DIRECT_FORWARDING_IGNORED,
                spoofed.decision());
    }

    @Test
    void trustedModeWalksRightToLeftAndStopsAtFirstUntrustedHop() throws Exception {
        TrustedProxyPolicy policy = TrustedProxyPolicy.trusted(
                List.of("10.0.0.0/8", "2001:db8:ffff::/48"), 8);

        PeerResolution resolution = policy.resolve(
                peer("10.0.0.5"),
                List.of("203.0.113.66, 198.51.100.7, 10.0.0.6"));

        assertTrue(resolution.accepted());
        assertEquals("198.51.100.7", resolution.clientAddress());
        assertEquals(
                PeerResolutionDecision.TRUSTED_FORWARDING,
                resolution.decision());

        PeerResolution ipv6 = policy.resolve(
                peer("2001:db8:ffff::1"), List.of("2001:db8:1::7"));
        assertTrue(ipv6.accepted());
        assertEquals(
                InetAddress.getByName("2001:db8:1::7").getHostAddress(),
                ipv6.clientAddress());
    }

    @Test
    void trustedModeRejectsMissingInvalidAndOversizedChains() throws Exception {
        TrustedProxyPolicy policy = TrustedProxyPolicy.trusted(
                List.of("10.0.0.0/8"), 2);

        assertRejected(
                policy.resolve(peer("10.0.0.5"), List.of()),
                PeerResolutionDecision.REJECTED_MISSING_FORWARDING);
        assertRejected(
                policy.resolve(peer("10.0.0.5"), List.of("client.example")),
                PeerResolutionDecision.REJECTED_INVALID_FORWARDING);
        assertRejected(
                policy.resolve(
                        peer("10.0.0.5"),
                        List.of("198.51.100.1, 198.51.100.2, 198.51.100.3")),
                PeerResolutionDecision.REJECTED_INVALID_FORWARDING);
        assertRejected(
                policy.resolve(null, List.of("198.51.100.1")),
                PeerResolutionDecision.REJECTED_MISSING_DIRECT_PEER);
    }

    @Test
    void rejectsAmbiguousOrOverbroadConfigurationShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> TrustedProxyPolicy.trusted(List.of(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> TrustedProxyPolicy.trusted(List.of("proxy.example/24"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> TrustedProxyPolicy.trusted(List.of("192.0.2.0/33"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> TrustedProxyPolicy.trusted(List.of("2001:db8::/129"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> TrustedProxyPolicy.trusted(List.of("192.0.2.0/24"), 17));
    }

    private static InetSocketAddress peer(String value) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(value), 443);
    }

    private static void assertRejected(
            PeerResolution resolution,
            PeerResolutionDecision expected) {
        assertFalse(resolution.accepted());
        assertEquals("", resolution.clientAddress());
        assertEquals(expected, resolution.decision());
    }
}
