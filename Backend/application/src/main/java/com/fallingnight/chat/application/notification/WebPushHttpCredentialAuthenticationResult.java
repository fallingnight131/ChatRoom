package com.fallingnight.chat.application.notification;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;

/** Fixed authentication outcome that never exposes presented or stored token material. */
public sealed interface WebPushHttpCredentialAuthenticationResult {
    record Authenticated(AuthenticatedDeviceActor actor)
            implements WebPushHttpCredentialAuthenticationResult {
        public Authenticated {
            if (actor == null) throw new NullPointerException("actor");
        }
    }

    enum Rejected implements WebPushHttpCredentialAuthenticationResult {
        INVALID_SESSION,
        INVALID_CSRF
    }
}
