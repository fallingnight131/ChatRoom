package com.fallingnight.chat.application.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Authenticated, transport-neutral desired state for one message reaction. */
public record MessageReactionCommand(
        UUID conversationId,
        UUID messageId,
        UUID actorAccountId,
        UUID actorDeviceId,
        MessageReactionKind reaction,
        boolean active,
        String clientOperationId) {
    public MessageReactionCommand {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(actorDeviceId, "actorDeviceId");
        Objects.requireNonNull(reaction, "reaction");
        Objects.requireNonNull(clientOperationId, "clientOperationId");
        int operationBytes = clientOperationId.getBytes(StandardCharsets.UTF_8).length;
        if (clientOperationId.isBlank() || operationBytes > 128) {
            throw new IllegalArgumentException(
                    "clientOperationId UTF-8 length must be 1..128");
        }
    }
}
