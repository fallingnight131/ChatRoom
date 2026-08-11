package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HttpHostPolicyTest {
    @Test
    void normalizesDefaultTlsPortAndRequiresOneExactAuthority() {
        HttpHostPolicy policy = new HttpHostPolicy(List.of(
                "gateway.example.com", "preview.example.com:8443", "[2001:db8::1]"));
        assertTrue(policy.allows(List.of("GATEWAY.example.com:443")));
        assertTrue(policy.allows(List.of("preview.example.com:8443")));
        assertTrue(policy.allows(List.of("[2001:db8::1]:443")));
        assertFalse(policy.allows(List.of()));
        assertFalse(policy.allows(List.of("gateway.example.com", "evil.example")));
        assertFalse(policy.allows(List.of("evil.example")));
        assertFalse(policy.allows(List.of("gateway.example.com/path")));
    }

    @Test
    void rejectsAmbiguousConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new HttpHostPolicy(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHostPolicy(List.of("https://gateway.example.com")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHostPolicy(List.of("user@gateway.example.com")));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpHostPolicy(List.of(
                        "gateway.example.com", "GATEWAY.example.com:443")));
    }
}
