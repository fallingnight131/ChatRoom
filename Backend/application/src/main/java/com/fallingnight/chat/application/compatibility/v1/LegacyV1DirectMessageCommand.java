package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Server-bound V1 direct-message intent after transport decoding. */
public record LegacyV1DirectMessageCommand(
        UUID senderAccountId,
        UUID senderDeviceId,
        String targetUsername,
        String clientMessageId,
        String content,
        String contentType) {
    public LegacyV1DirectMessageCommand {
        Objects.requireNonNull(senderAccountId, "senderAccountId");
        Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        Objects.requireNonNull(targetUsername, "targetUsername");
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentType, "contentType");
    }
}
