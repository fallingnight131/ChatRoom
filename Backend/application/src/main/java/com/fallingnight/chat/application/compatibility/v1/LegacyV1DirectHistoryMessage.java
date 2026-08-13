package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/** One UUID-free V1 direct text/emoji or attachment history projection. */
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
        long legacyFileId,
        String fileName,
        long fileSize,
        boolean fileCleared,
        String clearReason,
        boolean recalled,
        Instant acceptedAt) {
    public LegacyV1DirectHistoryMessage(long legacyMessageId, long sequence,
            Long mutationSequence, long syncSequence, String clientMessageId,
            String senderUsername, String senderDisplayName, String content,
            String contentType, boolean recalled, Instant acceptedAt) {
        this(legacyMessageId, sequence, mutationSequence, syncSequence, clientMessageId,
                senderUsername, senderDisplayName, content, contentType,
                0, "", 0, false, "", recalled, acceptedAt);
    }

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
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(clearReason, "clearReason");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (recalled != (mutationSequence != null) || syncSequence !=
                (mutationSequence == null ? sequence : mutationSequence)) {
            throw new IllegalArgumentException("direct history mutation projection");
        }
        boolean attachment = contentType.equals("file") || contentType.equals("image")
                || contentType.equals("video");
        if (legacyFileId < 0
                || attachment != (legacyFileId > 0)
                || legacyFileId > Integer.MAX_VALUE
                || attachment != !fileName.isEmpty()
                || fileName.getBytes(StandardCharsets.UTF_8).length > 255
                || fileSize < 0
                || attachment != (fileSize > 0)
                || fileSize > 10_737_418_240L
                || fileCleared && !attachment
                || fileCleared && clearReason.isBlank()
                || !fileCleared && !clearReason.isEmpty()
                || attachment && !content.equals(fileName)
                || !attachment && (!contentType.equals("text") && !contentType.equals("emoji"))) {
            throw new IllegalArgumentException("direct history attachment projection");
        }
    }
}
