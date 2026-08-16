package com.fallingnight.chat.application.contact;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Current identity projection for one durable outgoing block edge. */
public record AccountBlockSummary(
        UUID targetAccountId,
        String targetDisplayName,
        Instant blockedAt) {
    public static final int MAX_DISPLAY_NAME_UTF8_BYTES = 400;

    public AccountBlockSummary {
        Objects.requireNonNull(targetAccountId, "targetAccountId");
        targetDisplayName = Objects.requireNonNull(targetDisplayName, "targetDisplayName");
        Objects.requireNonNull(blockedAt, "blockedAt");
        if (!blockedAt.isAfter(Instant.EPOCH)) {
            throw new IllegalArgumentException("blockedAt must be after the epoch");
        }
        try {
            int bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(targetDisplayName)).remaining();
            int codePoints = targetDisplayName.codePointCount(0, targetDisplayName.length());
            if (targetDisplayName.isBlank() || codePoints > 100
                    || bytes < 1 || bytes > MAX_DISPLAY_NAME_UTF8_BYTES) {
                throw new IllegalArgumentException(
                        "targetDisplayName must contain 1..100 bounded characters");
            }
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "targetDisplayName must be valid UTF-8", exception);
        }
    }
}
