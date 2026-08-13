package com.fallingnight.chat.application.conversation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Current presentation metadata for one active conversation participant. */
public record ConversationParticipant(UUID accountId, String displayName, ConversationRole role) {
    public ConversationParticipant {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        int codePoints = displayName.codePointCount(0, displayName.length());
        int utf8Bytes = displayName.getBytes(StandardCharsets.UTF_8).length;
        if (displayName.isBlank() || codePoints > 100 || utf8Bytes > 400) {
            throw new IllegalArgumentException("displayName must contain 1..100 bounded characters");
        }
    }
}
