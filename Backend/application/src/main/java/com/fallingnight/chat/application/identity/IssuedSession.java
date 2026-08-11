package com.fallingnight.chat.application.identity;

import com.fallingnight.chat.application.security.SecretBytes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Server-issued session identity plus a one-time readable resume secret. */
public record IssuedSession(
        UUID accountId,
        UUID deviceId,
        UUID sessionId,
        SecretBytes resumeToken,
        Instant expiresAt,
        String displayName) implements AutoCloseable {
    public IssuedSession {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(resumeToken, "resumeToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(displayName, "displayName");
    }

    @Override
    public void close() {
        resumeToken.close();
    }
}
