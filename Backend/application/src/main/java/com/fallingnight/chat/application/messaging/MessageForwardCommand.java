package com.fallingnight.chat.application.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Authenticated intent to copy current server-owned text into one destination. */
public record MessageForwardCommand(
        UUID sourceConversationId,
        UUID sourceMessageId,
        int expectedSourceContentRevision,
        UUID targetConversationId,
        UUID actorAccountId,
        UUID actorDeviceId,
        String clientMessageId) {
    public MessageForwardCommand {
        Objects.requireNonNull(sourceConversationId, "sourceConversationId");
        Objects.requireNonNull(sourceMessageId, "sourceMessageId");
        Objects.requireNonNull(targetConversationId, "targetConversationId");
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(actorDeviceId, "actorDeviceId");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        if (expectedSourceContentRevision < 0
                || expectedSourceContentRevision > MessageEditCommand.MAX_REVISION) {
            throw new IllegalArgumentException("expected source revision is invalid");
        }
        int clientIdBytes = clientMessageId.getBytes(StandardCharsets.UTF_8).length;
        if (clientMessageId.isBlank() || clientIdBytes > 128) {
            throw new IllegalArgumentException("clientMessageId UTF-8 length must be 1..128");
        }
    }
}
