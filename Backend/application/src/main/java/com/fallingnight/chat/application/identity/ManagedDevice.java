package com.fallingnight.chat.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe active-device projection; contains no token, digest, IP, or client key. */
public record ManagedDevice(UUID deviceId, ClientPlatform platform,
        Instant createdAt, Instant lastSeenAt, boolean current) {
    public ManagedDevice {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        if (lastSeenAt.isBefore(createdAt))
            throw new IllegalArgumentException("device lastSeenAt precedes createdAt");
    }
}
