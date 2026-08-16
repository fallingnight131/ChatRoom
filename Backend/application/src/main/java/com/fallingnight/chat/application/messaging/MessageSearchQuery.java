package com.fallingnight.chat.application.messaging;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Authenticated, bounded literal search within one conversation. */
public record MessageSearchQuery(
        UUID conversationId,
        UUID accountId,
        String literalQuery,
        long beforeSequence,
        int limit) {
    public static final int MAX_QUERY_UTF8_BYTES = 128;
    public static final int MAX_LIMIT = 50;

    public MessageSearchQuery {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(accountId, "accountId");
        literalQuery = Objects.requireNonNull(literalQuery, "literalQuery").strip();
        if (beforeSequence < 0) {
            throw new IllegalArgumentException("beforeSequence must be nonnegative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be in 1..50");
        }
        try {
            int bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(literalQuery)).remaining();
            if (bytes < 1 || bytes > MAX_QUERY_UTF8_BYTES) {
                throw new IllegalArgumentException(
                        "literalQuery must contain 1..128 UTF-8 bytes");
            }
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("literalQuery must be valid UTF-8", exception);
        }
    }
}
