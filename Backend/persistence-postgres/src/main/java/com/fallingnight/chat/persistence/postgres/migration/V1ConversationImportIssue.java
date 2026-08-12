package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.util.Objects;

/** Safe blocking issue without usernames, room names, or message content. */
public record V1ConversationImportIssue(
        LegacyV1ConversationKind kind, long legacyId, String code, String message) {
    public V1ConversationImportIssue {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
