package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;

/** Deterministic target-ready text message/device plan; still performs no writes. */
public record V1MessageTargetImportPlan(
        List<PlannedV1LegacyDevice> legacyDevices,
        List<PlannedV1HistoricalMessage> messages,
        List<PlannedV1DeletionEvent> deletionEvents,
        List<PlannedV1ConversationCursor> conversationCursors,
        List<PlannedV1MemberReadCursor> memberReadCursors) {
    public V1MessageTargetImportPlan {
        legacyDevices = List.copyOf(legacyDevices);
        messages = List.copyOf(messages);
        deletionEvents = List.copyOf(deletionEvents);
        conversationCursors = List.copyOf(conversationCursors);
        memberReadCursors = List.copyOf(memberReadCursors);
    }
}
