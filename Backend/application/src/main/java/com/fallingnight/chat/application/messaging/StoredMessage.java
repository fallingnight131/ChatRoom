package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
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
        Instant acceptedAt) {
    public StoredMessage {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(senderAccountId, "senderAccountId");
        Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (conversationSequence < 1 || messageType < 1) {
            throw new IllegalArgumentException("stored message identity is invalid");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
