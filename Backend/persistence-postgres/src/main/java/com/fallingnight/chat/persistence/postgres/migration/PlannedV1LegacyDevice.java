package com.fallingnight.chat.persistence.postgres.migration;

import java.util.UUID;

/** Deterministic non-authenticating provenance device for one imported V1 sender. */
public record PlannedV1LegacyDevice(
        UUID accountId,
        UUID deviceId,
        String clientDeviceId) {}
