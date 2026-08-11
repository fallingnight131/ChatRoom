package com.fallingnight.chat.application.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One bounded descending conversation page and its next stable cursor. */
public record ConversationDirectoryPage(
        List<ConversationSummary> conversations,
        Optional<ConversationDirectoryCursor> next,
        boolean hasMore) {
    public ConversationDirectoryPage {
        conversations = List.copyOf(Objects.requireNonNull(conversations, "conversations"));
        next = Objects.requireNonNull(next, "next");
        if (conversations.size() > ConversationDirectoryQuery.MAX_LIMIT
                || (hasMore && conversations.isEmpty())
                || (conversations.isEmpty() && next.isPresent())) {
            throw new IllegalArgumentException("conversation page bounds are invalid");
        }
        if (!conversations.isEmpty()) {
            ConversationSummary last = conversations.getLast();
            ConversationDirectoryCursor expected = new ConversationDirectoryCursor(
                    last.updatedAt(), last.conversationId());
            if (!next.equals(Optional.of(expected))) {
                throw new IllegalArgumentException("next cursor must identify the last row");
            }
        }
    }
}
