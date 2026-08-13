package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Arrays;
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
        Optional<MessageReplyReference> reply) {
    public StoredMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(senderAccountId, "senderAccountId");
        Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        reply = Objects.requireNonNull(reply, "reply");
        if (conversationSequence < 1 || messageType < 1) {
            throw new IllegalArgumentException("stored message identity is invalid");
        }
        if (reply.isPresent()
                && reply.orElseThrow().targetConversationSequence()
                        >= conversationSequence) {
            throw new IllegalArgumentException(
                    "reply target sequence must precede stored message");
        }
        payload = Arrays.copyOf(payload, payload.length);
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
                Optional.empty());
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
