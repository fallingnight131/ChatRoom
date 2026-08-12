package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;

/** Immutable V1 sequence metadata paired with the already-validated conversation plan. */
public record V1MessageStateSourceSnapshot(
        V1ConversationImportPlan conversationPlan,
        List<V1ConversationWatermarkRow> watermarks,
        List<V1MessageCursorRow> messages,
        List<V1RoomDeletionCursorRow> roomDeletionEvents) {
    public V1MessageStateSourceSnapshot {
        watermarks = List.copyOf(watermarks);
        messages = List.copyOf(messages);
        roomDeletionEvents = List.copyOf(roomDeletionEvents);
    }
}
