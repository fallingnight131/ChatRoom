package com.fallingnight.chat.application.messaging;

import java.util.Objects;
import java.util.UUID;

/** Server-authored durable identity of the message targeted by a reply. */
public record MessageReplyReference(
        UUID targetMessageId,
        long targetConversationSequence,
        UUID targetSenderAccountId) {
    public MessageReplyReference {
        Objects.requireNonNull(targetMessageId, "targetMessageId");
        Objects.requireNonNull(targetSenderAccountId, "targetSenderAccountId");
        if (targetConversationSequence < 1) {
            throw new IllegalArgumentException("targetConversationSequence must be positive");
        }
    }
}
