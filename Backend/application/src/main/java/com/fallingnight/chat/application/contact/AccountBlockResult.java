package com.fallingnight.chat.application.contact;

import java.util.Objects;
import java.util.UUID;

public sealed interface AccountBlockResult {
    record Applied(
            UUID actorAccountId,
            UUID targetAccountId,
            boolean blocked,
            boolean changed,
            UUID clientOperationId) implements AccountBlockResult {
        public Applied {
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            Objects.requireNonNull(targetAccountId, "targetAccountId");
            Objects.requireNonNull(clientOperationId, "clientOperationId");
        }
    }

    record OperationConflict(UUID clientOperationId) implements AccountBlockResult {
        public OperationConflict {
            Objects.requireNonNull(clientOperationId, "clientOperationId");
        }
    }

    enum Rejected implements AccountBlockResult {
        SELF_BLOCK,
        TARGET_UNAVAILABLE
    }
}
