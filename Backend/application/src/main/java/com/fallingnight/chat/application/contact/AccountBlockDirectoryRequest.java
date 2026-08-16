package com.fallingnight.chat.application.contact;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Client-selectable pagination only; actor identity is deliberately absent. */
public record AccountBlockDirectoryRequest(
        Optional<UUID> afterTargetAccountId,
        int limit) {
    public AccountBlockDirectoryRequest {
        afterTargetAccountId = Objects.requireNonNull(
                afterTargetAccountId, "afterTargetAccountId");
        if (limit < 1 || limit > AccountBlockDirectoryQuery.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in 1..100");
        }
    }
}
