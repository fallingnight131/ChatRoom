package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Temporary retained V1 message identity paired with canonical V2 identities. */
public record LegacyV1MessageIdentity(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        UUID conversationId,
        UUID messageId) {
    public LegacyV1MessageIdentity {
        Objects.requireNonNull(legacyKind, "legacyKind");
        if (legacyConversationId <= 0 || legacyMessageId <= 0) {
            throw new IllegalArgumentException("legacy identifiers must be positive");
        }
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(messageId, "messageId");
    }
}
