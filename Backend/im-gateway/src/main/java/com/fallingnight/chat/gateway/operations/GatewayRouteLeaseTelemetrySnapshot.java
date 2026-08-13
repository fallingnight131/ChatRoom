package com.fallingnight.chat.gateway.operations;

public record GatewayRouteLeaseTelemetrySnapshot(
        long attempts, long renewed, long failed, int consecutiveFailures,
        boolean leaseValid, long nextDelayMillis) { }
