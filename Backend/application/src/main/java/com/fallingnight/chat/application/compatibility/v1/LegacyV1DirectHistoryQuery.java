package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Raw V1 direct-history intent; the service applies compatibility validation. */
public record LegacyV1DirectHistoryQuery(
        UUID accountId,
        String targetUsername,
        int limit,
        long beforeEpochMillis,
        Long afterSequence) { }
