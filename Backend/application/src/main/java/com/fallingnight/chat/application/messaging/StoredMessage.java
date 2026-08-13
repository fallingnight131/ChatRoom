package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative durable message projection. */
public record StoredMessage(
        UUID messageId,
        UUID conversationId,
        long conversationSequence,
        UUID senderAccountId,
        UUID senderDeviceId,
        String clientMessageId,
        int messageType,
        byte[] payload,
        Instant acceptedAt,
        Optional<MessageReplyReference> reply,
        int contentRevision,
        Optional<Instant> editedAt,
        List<MessageMention> mentions) {
    public StoredMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(senderAccountId, "senderAccountId");
        Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        reply = Objects.requireNonNull(reply, "reply");
        editedAt = Objects.requireNonNull(editedAt, "editedAt");
        mentions = MessageMentionPolicy.validateAndCopy(payload, mentions);
        if (conversationSequence < 1 || messageType < 1) {
            throw new IllegalArgumentException("stored message identity is invalid");
        }
        if (reply.isPresent()
                && reply.orElseThrow().targetConversationSequence()
                        >= conversationSequence) {
            throw new IllegalArgumentException(
                    "reply target sequence must precede stored message");
        }
        if (contentRevision < 0 || contentRevision > MessageEditCommand.MAX_REVISION
                || (contentRevision == 0) != editedAt.isEmpty()) {
            throw new IllegalArgumentException("stored message edit metadata is invalid");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }

    public StoredMessage(
            UUID messageId, UUID conversationId, long conversationSequence,
            UUID senderAccountId, UUID senderDeviceId, String clientMessageId,
            int messageType, byte[] payload, Instant acceptedAt,
            Optional<MessageReplyReference> reply, int contentRevision,
            Optional<Instant> editedAt) {
        this(messageId, conversationId, conversationSequence, senderAccountId,
                senderDeviceId, clientMessageId, messageType, payload, acceptedAt,
                reply, contentRevision, editedAt, List.of());
    }

    public StoredMessage(
            UUID messageId,
            UUID conversationId,
            long conversationSequence,
            UUID senderAccountId,
            UUID senderDeviceId,
            String clientMessageId,
            int messageType,
            byte[] payload,
            Instant acceptedAt,
            Optional<MessageReplyReference> reply) {
        this(messageId, conversationId, conversationSequence, senderAccountId,
                senderDeviceId, clientMessageId, messageType, payload, acceptedAt,
                reply, 0, Optional.empty(), List.of());
    }

    public StoredMessage(
            UUID messageId,
            UUID conversationId,
            long conversationSequence,
            UUID senderAccountId,
            UUID senderDeviceId,
            String clientMessageId,
            int messageType,
            byte[] payload,
            Instant acceptedAt) {
        this(messageId, conversationId, conversationSequence, senderAccountId,
                senderDeviceId, clientMessageId, messageType, payload, acceptedAt,
                Optional.empty(), 0, Optional.empty(), List.of());
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
