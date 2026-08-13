package com.fallingnight.chat.application.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authenticated, transport-neutral intent to append one durable message. */
public record MessageSubmission(
        UUID conversationId,
        UUID senderAccountId,
        UUID senderDeviceId,
        String clientMessageId,
        int messageType,
        byte[] payload,
        Optional<UUID> replyToMessageId) {
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;

    public MessageSubmission {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(senderAccountId, "senderAccountId");
        Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        Objects.requireNonNull(payload, "payload");
        replyToMessageId = Objects.requireNonNull(replyToMessageId, "replyToMessageId");
        if (clientMessageId.isBlank()
                || clientMessageId.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new IllegalArgumentException("clientMessageId UTF-8 length must be 1..128");
        }
        if (messageType < 1) {
            throw new IllegalArgumentException("messageType must be positive");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("message payload is too large");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }

    public MessageSubmission(
            UUID conversationId,
            UUID senderAccountId,
            UUID senderDeviceId,
            String clientMessageId,
            int messageType,
            byte[] payload) {
        this(conversationId, senderAccountId, senderDeviceId, clientMessageId,
                messageType, payload, Optional.empty());
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
