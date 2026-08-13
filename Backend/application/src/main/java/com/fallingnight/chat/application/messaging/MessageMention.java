package com.fallingnight.chat.application.messaging;

import java.util.Objects;
import java.util.UUID;

/** Stable mention identity and half-open span over the exact UTF-8 message body. */
public record MessageMention(UUID targetAccountId, int startUtf8Byte, int lengthUtf8Bytes) {
    public MessageMention {
        Objects.requireNonNull(targetAccountId, "targetAccountId");
        if (startUtf8Byte < 0) {
            throw new IllegalArgumentException("startUtf8Byte must be nonnegative");
        }
        if (lengthUtf8Bytes < 1) {
            throw new IllegalArgumentException("lengthUtf8Bytes must be positive");
        }
    }

    public long endUtf8Byte() {
        return (long) startUtf8Byte + lengthUtf8Bytes;
    }
}
