package com.fallingnight.chat.application.identity;

import java.util.Objects;

/** Negotiated, untrusted client metadata passed to session policy. */
public record ClientDescriptor(
        String clientDeviceId,
        ClientPlatform platform,
        String appVersion) {
    public ClientDescriptor {
        if (clientDeviceId == null || clientDeviceId.isBlank()) {
            throw new IllegalArgumentException("clientDeviceId is required");
        }
        Objects.requireNonNull(platform, "platform");
        if (appVersion == null || appVersion.isBlank()) {
            throw new IllegalArgumentException("appVersion is required");
        }
    }
}
