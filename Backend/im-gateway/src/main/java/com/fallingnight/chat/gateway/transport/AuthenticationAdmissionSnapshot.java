package com.fallingnight.chat.gateway.transport;

/** Non-secret process-local limiter state suitable for metrics collection. */
public record AuthenticationAdmissionSnapshot(
        long allowedAttempts,
        long deniedAttempts,
        long gatewayDenials,
        long directPeerDenials,
        long accountDenials,
        long capacityDenials,
        int activeDirectPeerKeys,
        int activeAccountKeys) {
}
