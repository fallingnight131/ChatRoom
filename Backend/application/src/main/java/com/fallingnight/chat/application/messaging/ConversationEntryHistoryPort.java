package com.fallingnight.chat.application.messaging;

/** Reads the authoritative mixed conversation sequence without skipping mutations. */
public interface ConversationEntryHistoryPort {
    ConversationEntryHistoryResult readEntriesAfter(MessageHistoryQuery query);
}
