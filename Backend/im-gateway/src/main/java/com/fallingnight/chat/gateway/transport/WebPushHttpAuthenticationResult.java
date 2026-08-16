package com.fallingnight.chat.gateway.transport;

/** Fixed authentication result that never exposes token or CSRF material. */
public sealed interface WebPushHttpAuthenticationResult {
    record Authenticated(WebPushHttpActor actor) implements WebPushHttpAuthenticationResult {
        public Authenticated {
            if (actor == null) throw new NullPointerException("actor");
        }
    }

    enum Rejected implements WebPushHttpAuthenticationResult {
        INVALID_SESSION,
        INVALID_CSRF
    }
}
