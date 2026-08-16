package com.fallingnight.chat.application.contact;

import java.util.Objects;
import java.util.UUID;

/** Server-bound durable mutation; actor identity never comes from the payload. */
public record AccountBlockMutation(
        UUID actorAccountId,
        UUID targetAccountId,
        boolean blocked,
        UUID clientOperationId) {
    public AccountBlockMutation {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(targetAccountId, "targetAccountId");
        Objects.requireNonNull(clientOperationId, "clientOperationId");
        if (actorAccountId.equals(targetAccountId)) {
            throw new IllegalArgumentException("an account cannot block itself");
        }
    }
}
