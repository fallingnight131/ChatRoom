package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Temporary V1 conversation identity paired with its canonical V2 UUID. */
public record LegacyV1ConversationIdentity(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        UUID conversationId) {
    public LegacyV1ConversationIdentity {
        Objects.requireNonNull(legacyKind, "legacyKind");
        if (legacyConversationId <= 0) {
            throw new IllegalArgumentException("legacyConversationId must be positive");
        }
        Objects.requireNonNull(conversationId, "conversationId");
    }
}
