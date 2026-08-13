package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Exact durable pin outcome, opaque denial, bound rejection, or key conflict. */
public sealed interface MessagePinResult {
    record Applied(
            UUID conversationId,
            UUID messageId,
            UUID actorAccountId,
            boolean pinned,
            String clientOperationId,
            boolean changed,
            long conversationSequence,
            Instant occurredAt,
            boolean duplicate) implements MessagePinResult {
        public Applied {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            Objects.requireNonNull(clientOperationId, "clientOperationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (changed != (conversationSequence > 0)) {
                throw new IllegalArgumentException(
                        "changed pins require a positive sequence and no-ops require zero");
            }
        }
    }

    enum Rejected implements MessagePinResult {
        NOT_AUTHORIZED,
        LIMIT_REACHED,
        IDEMPOTENCY_CONFLICT
    }
}
