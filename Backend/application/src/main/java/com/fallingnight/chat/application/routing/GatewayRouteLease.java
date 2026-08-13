package com.fallingnight.chat.application.routing;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Short boot-identity lease proving a gateway may own reconstructable routes. */
public record GatewayRouteLease(UUID gatewayId, Instant renewedAt, Instant expiresAt) {
    public static final Duration MAX_LEASE = Duration.ofMinutes(1);

    public GatewayRouteLease {
        Objects.requireNonNull(gatewayId, "gatewayId");
        Objects.requireNonNull(renewedAt, "renewedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(renewedAt)
                || Duration.between(renewedAt, expiresAt).compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("invalid gateway route lease");
        }
    }
}
