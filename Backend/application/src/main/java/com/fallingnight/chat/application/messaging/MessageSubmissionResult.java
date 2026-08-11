package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable acceptance, opaque authorization denial, or idempotency-key conflict. */
public sealed interface MessageSubmissionResult {
    record Accepted(
            UUID messageId,
            long conversationSequence,
            Instant acceptedAt,
            boolean duplicate) implements MessageSubmissionResult {
        public Accepted {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            if (conversationSequence < 1) {
                throw new IllegalArgumentException("conversationSequence must be positive");
            }
        }
    }

    enum Rejected implements MessageSubmissionResult {
        NOT_AUTHORIZED,
        IDEMPOTENCY_CONFLICT
    }
}
