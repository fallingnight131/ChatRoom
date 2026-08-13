package com.fallingnight.chat.application.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Authenticated, transport-neutral desired pin state for one message. */
public record MessagePinCommand(
        UUID conversationId,
        UUID messageId,
        UUID actorAccountId,
        UUID actorDeviceId,
        boolean pinned,
        String clientOperationId) {
    public MessagePinCommand {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(actorDeviceId, "actorDeviceId");
        Objects.requireNonNull(clientOperationId, "clientOperationId");
        int bytes = clientOperationId.getBytes(StandardCharsets.UTF_8).length;
        if (clientOperationId.isBlank() || bytes > 128) {
            throw new IllegalArgumentException("clientOperationId UTF-8 length must be 1..128");
        }
    }
}
