package com.fallingnight.chat.gateway.transport;

import java.time.Instant;

/** Synchronous server-issued, revocable session and CSRF verification boundary. */
@FunctionalInterface
public interface WebPushHttpSessionPort {
    WebPushHttpAuthenticationResult authenticate(
            byte[] bearerToken, byte[] csrfToken, Instant observedAt);
}
