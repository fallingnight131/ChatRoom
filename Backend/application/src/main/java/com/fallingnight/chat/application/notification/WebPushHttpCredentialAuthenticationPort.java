package com.fallingnight.chat.application.notification;

import java.time.Instant;

/** Verifies one presented HTTP bearer/CSRF pair against current server session truth. */
@FunctionalInterface
public interface WebPushHttpCredentialAuthenticationPort {
    WebPushHttpCredentialAuthenticationResult authenticate(
            byte[] bearerToken, byte[] csrfToken, Instant observedAt);
}
