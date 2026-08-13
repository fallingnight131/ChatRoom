package com.fallingnight.chat.application.conversation;

@FunctionalInterface
public interface ConversationParticipantPort {
    ConversationParticipantResult list(ConversationParticipantQuery query);
}
