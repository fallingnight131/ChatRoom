package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One durable conversation-sequence entry, independent of transport encoding. */
public sealed interface ConversationHistoryEntry {
    UUID conversationId();

    long conversationSequence();

    record Message(StoredMessage value) implements ConversationHistoryEntry {
        public Message {
            Objects.requireNonNull(value, "value");
        }

        @Override public UUID conversationId() { return value.conversationId(); }
        @Override public long conversationSequence() { return value.conversationSequence(); }
    }

    record Recall(
            UUID conversationId,
            long conversationSequence,
            UUID messageId,
            UUID actorAccountId,
            String source,
            Optional<Instant> occurredAt) implements ConversationHistoryEntry {
        public Recall {
            requireIdentity(conversationId, conversationSequence, actorAccountId, source);
            Objects.requireNonNull(messageId, "messageId");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    record Deletion(
            UUID conversationId,
            long conversationSequence,
            UUID actorAccountId,
            String source,
            String mode,
            String clientOperationId,
            List<UUID> messageIds,
            long cutoffEpochMs,
            int deletedCount,
            String operatorNameSnapshot,
            Instant occurredAt) implements ConversationHistoryEntry {
        public Deletion {
            requireIdentity(conversationId, conversationSequence, actorAccountId, source);
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(clientOperationId, "clientOperationId");
            messageIds = List.copyOf(Objects.requireNonNull(messageIds, "messageIds"));
            Objects.requireNonNull(operatorNameSnapshot, "operatorNameSnapshot");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (cutoffEpochMs < 0 || deletedCount < 0) {
                throw new IllegalArgumentException("deletion history bounds are invalid");
            }
        }
    }

    private static void requireIdentity(
            UUID conversationId, long sequence, UUID actorAccountId, String source) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(source, "source");
        if (sequence < 1 || source.isBlank()) {
            throw new IllegalArgumentException("conversation history identity is invalid");
        }
    }
}
