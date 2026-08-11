package com.fallingnight.chat.application.messaging;

/** Reads a bounded, sequence-ordered page for an active conversation member. */
public interface MessageHistoryPort {
    MessageHistoryResult readAfter(MessageHistoryQuery query);
}
