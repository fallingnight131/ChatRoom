package com.fallingnight.chat.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface DeviceRevocationResult {
    record Revoked(UUID targetDeviceId, UUID auditId, Instant revokedAt,
            int revokedSessions, boolean changed) implements DeviceRevocationResult {
        public Revoked {
            Objects.requireNonNull(targetDeviceId, "targetDeviceId");
            Objects.requireNonNull(auditId, "auditId");
            Objects.requireNonNull(revokedAt, "revokedAt");
            if (revokedSessions < 0)
                throw new IllegalArgumentException("revokedSessions is negative");
        }
    }
    enum Rejected implements DeviceRevocationResult { INSTANCE }
}
