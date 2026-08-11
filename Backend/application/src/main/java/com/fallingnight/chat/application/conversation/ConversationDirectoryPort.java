package com.fallingnight.chat.application.conversation;

@FunctionalInterface
public interface ConversationDirectoryPort {
    ConversationDirectoryPage list(ConversationDirectoryQuery query);
}
