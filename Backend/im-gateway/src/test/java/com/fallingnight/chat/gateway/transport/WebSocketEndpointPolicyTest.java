package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fallingnight.chat.application.identity.ClientPlatform;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebSocketEndpointPolicyTest {
    @Test
    void separatesBrowserAndWindowsEndpointTrust() {
        WebSocketEndpointPolicy policy = new WebSocketEndpointPolicy(List.of(
                "https://chat.example.com",
                "https://preview.example.com:8443"));

        assertEquals(ClientPlatform.WEB, policy.expectedPlatform(
                WebSocketEndpointPolicy.WEB_PATH,
                List.of("https://CHAT.example.com:443")));
        assertEquals(ClientPlatform.WINDOWS, policy.expectedPlatform(
                WebSocketEndpointPolicy.WINDOWS_PATH, List.of()));
        assertThrows(IllegalArgumentException.class, () -> policy.expectedPlatform(
                WebSocketEndpointPolicy.WEB_PATH, List.of()));
        assertThrows(IllegalArgumentException.class, () -> policy.expectedPlatform(
                WebSocketEndpointPolicy.WEB_PATH, List.of("https://evil.example")));
        assertThrows(IllegalArgumentException.class, () -> policy.expectedPlatform(
                WebSocketEndpointPolicy.WEB_PATH,
                List.of("https://chat.example.com", "https://chat.example.com")));
        assertThrows(IllegalArgumentException.class, () -> policy.expectedPlatform(
                WebSocketEndpointPolicy.WINDOWS_PATH,
                List.of("https://chat.example.com")));
        assertThrows(IllegalArgumentException.class, () -> policy.expectedPlatform(
                "/v2/other", List.of("https://chat.example.com")));
    }

    @Test
    void rejectsInsecureAmbiguousAndNonOriginConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketEndpointPolicy(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketEndpointPolicy(List.of("http://chat.example.com")));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketEndpointPolicy(List.of("https://chat.example.com/path")));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketEndpointPolicy(List.of("https://user@chat.example.com")));
        assertThrows(IllegalArgumentException.class,
                () -> new WebSocketEndpointPolicy(List.of(
                        "https://chat.example.com", "https://CHAT.example.com:443")));
    }
}
