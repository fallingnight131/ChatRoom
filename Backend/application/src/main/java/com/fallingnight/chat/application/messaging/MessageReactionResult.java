package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact durable reaction outcome, opaque denial, or operation-key conflict. */
public sealed interface MessageReactionResult {
    record Applied(
            UUID conversationId,
            UUID messageId,
            UUID actorAccountId,
            MessageReactionKind reaction,
            boolean active,
            String clientOperationId,
            boolean changed,
            long conversationSequence,
            Instant occurredAt,
            boolean duplicate) implements MessageReactionResult {
        public Applied {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            Objects.requireNonNull(reaction, "reaction");
            Objects.requireNonNull(clientOperationId, "clientOperationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (changed != (conversationSequence > 0)) {
                throw new IllegalArgumentException(
                        "changed reactions require a positive sequence and no-ops require zero");
            }
        }
    }

    enum Rejected implements MessageReactionResult {
        NOT_AUTHORIZED,
        IDEMPOTENCY_CONFLICT
    }
}
