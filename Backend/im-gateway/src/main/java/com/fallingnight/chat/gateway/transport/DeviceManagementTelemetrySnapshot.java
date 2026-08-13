package com.fallingnight.chat.gateway.transport;

public record DeviceManagementTelemetrySnapshot(long listed, long revoked, long duplicate,
        long disconnected, long denied, long invalid, long saturated, long failed) { }
