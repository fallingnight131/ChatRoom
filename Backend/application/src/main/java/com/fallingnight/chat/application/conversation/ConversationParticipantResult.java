package com.fallingnight.chat.application.conversation;

/** Authorized participant page or a fixed authorization denial. */
public sealed interface ConversationParticipantResult {
    record Found(ConversationParticipantPage page) implements ConversationParticipantResult {}

    enum Rejected implements ConversationParticipantResult {
        NOT_AUTHORIZED
    }
}
