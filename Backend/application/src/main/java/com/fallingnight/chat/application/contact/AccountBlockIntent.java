package com.fallingnight.chat.application.contact;

import java.util.Objects;
import java.util.UUID;

/** Client-supplied portion of one idempotent account-block mutation. */
public record AccountBlockIntent(
        UUID targetAccountId,
        boolean blocked,
        UUID clientOperationId) {
    public AccountBlockIntent {
        Objects.requireNonNull(targetAccountId, "targetAccountId");
        Objects.requireNonNull(clientOperationId, "clientOperationId");
    }
}
