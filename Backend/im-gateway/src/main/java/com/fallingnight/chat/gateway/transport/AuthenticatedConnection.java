package com.fallingnight.chat.gateway.transport;

import java.util.Objects;
import java.util.UUID;

/** Server-bound identity; envelope session fields never replace this authority. */
public record AuthenticatedConnection(
        UUID accountId,
        UUID deviceId,
        UUID sessionId) {
    public AuthenticatedConnection {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
