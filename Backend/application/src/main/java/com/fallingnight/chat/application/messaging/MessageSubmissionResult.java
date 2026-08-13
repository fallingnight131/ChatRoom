package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable acceptance, opaque authorization denial, or idempotency-key conflict. */
public sealed interface MessageSubmissionResult {
    record Accepted(
            UUID messageId,
            long conversationSequence,
            Instant acceptedAt,
            boolean duplicate,
            Optional<MessageReplyReference> reply) implements MessageSubmissionResult {
        public Accepted {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
            reply = Objects.requireNonNull(reply, "reply");
            if (conversationSequence < 1) {
                throw new IllegalArgumentException("conversationSequence must be positive");
            }
            if (reply.isPresent()
                    && reply.orElseThrow().targetConversationSequence()
                            >= conversationSequence) {
                throw new IllegalArgumentException(
                        "reply target sequence must precede acceptance");
            }
        }

        public Accepted(
                UUID messageId,
                long conversationSequence,
                Instant acceptedAt,
                boolean duplicate) {
            this(messageId, conversationSequence, acceptedAt, duplicate, Optional.empty());
        }
    }

    enum Rejected implements MessageSubmissionResult {
        NOT_AUTHORIZED,
        IDEMPOTENCY_CONFLICT
    }
}
