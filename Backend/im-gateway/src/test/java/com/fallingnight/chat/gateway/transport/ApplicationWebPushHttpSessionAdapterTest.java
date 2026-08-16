package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialAuthenticationResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplicationWebPushHttpSessionAdapterTest {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    @Test
    void forwardsOwnedTransportBytesAndMapsOnlyTheServerBoundActor() {
        byte[] bearer = new byte[] {1};
        byte[] csrf = new byte[] {2};
        var adapter = new ApplicationWebPushHttpSessionAdapter((observedBearer,
                observedCsrf, observedAt) -> {
            assertSame(bearer, observedBearer);
            assertSame(csrf, observedCsrf);
            assertEquals(NOW, observedAt);
            return new WebPushHttpCredentialAuthenticationResult.Authenticated(
                    new AuthenticatedDeviceActor(ACCOUNT, DEVICE, SESSION));
        });
        var authenticated = (WebPushHttpAuthenticationResult.Authenticated)
                adapter.authenticate(bearer, csrf, NOW);
        assertEquals(new WebPushHttpActor(ACCOUNT, SESSION), authenticated.actor());
    }

    @Test
    void preservesFixedCsrfAndSessionRejections() {
        assertEquals(WebPushHttpAuthenticationResult.Rejected.INVALID_CSRF,
                new ApplicationWebPushHttpSessionAdapter((bearer, csrf, at) ->
                        WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_CSRF)
                        .authenticate(new byte[0], new byte[0], NOW));
        assertEquals(WebPushHttpAuthenticationResult.Rejected.INVALID_SESSION,
                new ApplicationWebPushHttpSessionAdapter((bearer, csrf, at) ->
                        WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_SESSION)
                        .authenticate(new byte[0], new byte[0], NOW));
    }
}
