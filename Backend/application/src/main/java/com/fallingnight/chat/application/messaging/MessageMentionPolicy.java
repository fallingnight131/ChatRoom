package com.fallingnight.chat.application.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared application invariant for structured mention identity and UTF-8 spans. */
public final class MessageMentionPolicy {
    public static final int MAX_SPANS = 20;
    public static final int MAX_DISTINCT_TARGETS = 10;

    private MessageMentionPolicy() {}

    public static List<MessageMention> validateAndCopy(
            byte[] utf8Body, List<MessageMention> mentions) {
        Objects.requireNonNull(utf8Body, "utf8Body");
        Objects.requireNonNull(mentions, "mentions");
        List<MessageMention> copy = List.copyOf(mentions);
        if (copy.isEmpty()) {
            return copy;
        }
        requireValidUtf8(utf8Body);
        if (copy.size() > MAX_SPANS) {
            throw new IllegalArgumentException("mentions exceed span limit");
        }

        long previousEnd = 0;
        Set<UUID> targets = new HashSet<>();
        for (MessageMention mention : copy) {
            Objects.requireNonNull(mention, "mention");
            long end = mention.endUtf8Byte();
            if (mention.startUtf8Byte() < previousEnd || end > utf8Body.length
                    || !isUtf8Boundary(utf8Body, mention.startUtf8Byte())
                    || !isUtf8Boundary(utf8Body, end)
                    || utf8Body[mention.startUtf8Byte()] != '@') {
                throw new IllegalArgumentException(
                        "mention span is invalid, overlapping, or unordered");
            }
            targets.add(mention.targetAccountId());
            previousEnd = end;
        }
        if (targets.size() > MAX_DISTINCT_TARGETS) {
            throw new IllegalArgumentException("mentions exceed distinct target limit");
        }
        return copy;
    }

    public static void requireNoSelfMention(
            UUID actorAccountId, List<MessageMention> mentions) {
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(mentions, "mentions");
        if (mentions.stream().anyMatch(mention ->
                mention.targetAccountId().equals(actorAccountId))) {
            throw new IllegalArgumentException("self mention is not supported");
        }
    }

    private static boolean isUtf8Boundary(byte[] value, long index) {
        return index >= 0 && index <= value.length
                && (index == 0 || index == value.length
                    || (value[(int) index] & 0xc0) != 0x80);
    }

    private static void requireValidUtf8(byte[] value) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value));
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("mention body must be valid UTF-8", exception);
        }
    }
}
