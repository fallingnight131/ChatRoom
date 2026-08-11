package com.fallingnight.chat.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Structural bounds and stable ordering for conversation-directory payloads. */
public final class ConversationPayloadPolicy {
    public static final int MAX_LIMIT = 100;
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 100;
    public static final int MAX_DISPLAY_NAME_UTF8_BYTES = 400;

    private ConversationPayloadPolicy() {}

    public static void requireValid(ListConversations command) {
        boolean noCursor = command.getAfterUpdatedAtEpochMs() == 0
                && command.getAfterConversationId().isEmpty();
        boolean completeCursor = command.getAfterUpdatedAtEpochMs() > 0
                && canonicalUuid(command.getAfterConversationId());
        if ((!noCursor && !completeCursor)
                || command.getLimit() < 1
                || command.getLimit() > MAX_LIMIT) {
            throw new IllegalArgumentException("conversation directory command is invalid");
        }
    }

    public static void requireValid(ConversationDirectoryPage page) {
        if (page.getConversationsCount() > MAX_LIMIT
                || (page.getHasMore() && page.getConversationsCount() == 0)) {
            throw new IllegalArgumentException("conversation directory page bounds are invalid");
        }
        ConversationDirectoryRecord previous = null;
        for (ConversationDirectoryRecord record : page.getConversationsList()) {
            requireValid(record);
            if (previous != null && !strictlyAfter(previous, record)) {
                throw new IllegalArgumentException("conversation directory is out of order");
            }
            previous = record;
        }
        if (previous == null) {
            if (page.getNextUpdatedAtEpochMs() != 0
                    || !page.getNextConversationId().isEmpty()) {
                throw new IllegalArgumentException("empty directory page has a cursor");
            }
        } else if (page.getNextUpdatedAtEpochMs() != previous.getUpdatedAtEpochMs()
                || !page.getNextConversationId().equals(previous.getConversationId())) {
            throw new IllegalArgumentException("directory cursor must identify the last row");
        }
    }

    private static void requireValid(ConversationDirectoryRecord record) {
        int codePoints = record.getDisplayName().codePointCount(
                0, record.getDisplayName().length());
        int bytes = record.getDisplayName().getBytes(StandardCharsets.UTF_8).length;
        if (!canonicalUuid(record.getConversationId())
                || record.getKind() == ConversationKind.CONVERSATION_KIND_UNSPECIFIED
                || record.getKind() == ConversationKind.UNRECOGNIZED
                || record.getRole() == ConversationRole.CONVERSATION_ROLE_UNSPECIFIED
                || record.getRole() == ConversationRole.UNRECOGNIZED
                || record.getDisplayName().isBlank()
                || codePoints > MAX_DISPLAY_NAME_CODE_POINTS
                || bytes > MAX_DISPLAY_NAME_UTF8_BYTES
                || record.getLatestSequence() < 0
                || record.getLastReadSequence() < 0
                || record.getLastReadSequence() > record.getLatestSequence()
                || record.getUpdatedAtEpochMs() <= 0) {
            throw new IllegalArgumentException("conversation directory record is invalid");
        }
    }

    private static boolean strictlyAfter(
            ConversationDirectoryRecord previous, ConversationDirectoryRecord current) {
        return previous.getUpdatedAtEpochMs() > current.getUpdatedAtEpochMs()
                || (previous.getUpdatedAtEpochMs() == current.getUpdatedAtEpochMs()
                        && previous.getConversationId().compareTo(current.getConversationId()) > 0);
    }

    private static boolean canonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
