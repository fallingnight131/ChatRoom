package com.fallingnight.chat.application.compatibility.v1;

import java.time.Instant;
import java.util.Objects;

/** One UUID-free V1 direct text/emoji history projection. */
public record LegacyV1DirectHistoryMessage(
        long legacyMessageId,
        long sequence,
        Long mutationSequence,
        long syncSequence,
        String clientMessageId,
        String senderUsername,
        String senderDisplayName,
        String content,
        String contentType,
        boolean recalled,
        Instant acceptedAt) {
    public LegacyV1DirectHistoryMessage {
        if (legacyMessageId <= 0 || legacyMessageId > Integer.MAX_VALUE
                || sequence <= 0 || syncSequence < sequence
                || mutationSequence != null && mutationSequence <= sequence) {
            throw new IllegalArgumentException("direct history sequence identity");
        }
        Objects.requireNonNull(clientMessageId, "clientMessageId");
        Objects.requireNonNull(senderUsername, "senderUsername");
        Objects.requireNonNull(senderDisplayName, "senderDisplayName");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (recalled != (mutationSequence != null) || syncSequence !=
                (mutationSequence == null ? sequence : mutationSequence)) {
            throw new IllegalArgumentException("direct history mutation projection");
        }
    }
}
