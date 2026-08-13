package com.fallingnight.chat.application.identity;

import java.util.Objects;
import java.util.UUID;

/** Identity established by the gateway, never decoded from a command payload. */
public record AuthenticatedDeviceActor(UUID accountId, UUID deviceId, UUID sessionId) {
    public AuthenticatedDeviceActor {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
