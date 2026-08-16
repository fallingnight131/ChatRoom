package com.fallingnight.chat.gateway.transport;

import java.util.Objects;
import java.util.UUID;

/** Identity established only by the server-side HTTP session boundary. */
public record WebPushHttpActor(UUID accountId, UUID sessionId) {
    public WebPushHttpActor {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
