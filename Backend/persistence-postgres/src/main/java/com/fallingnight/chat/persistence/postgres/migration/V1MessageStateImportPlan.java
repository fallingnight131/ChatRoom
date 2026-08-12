package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;

/** Deterministic pre-write plan for V1 sequence high watermarks and read cursors. */
public record V1MessageStateImportPlan(
        String sourceFingerprintSha256,
        int sourceMessages,
        int sourceRoomDeletionEvents,
        List<V1MessageCursorRow> sourceMessageRows,
        List<V1RoomDeletionCursorRow> sourceDeletionEventRows,
        List<PlannedV1ConversationCursor> conversationCursors,
        List<PlannedV1MemberReadCursor> memberReadCursors,
        List<V1MessageStateImportIssue> issues) {
    public V1MessageStateImportPlan {
        java.util.Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        sourceMessageRows = List.copyOf(sourceMessageRows);
        sourceDeletionEventRows = List.copyOf(sourceDeletionEventRows);
        conversationCursors = List.copyOf(conversationCursors);
        memberReadCursors = List.copyOf(memberReadCursors);
        issues = List.copyOf(issues);
    }

    public boolean readyToCompareWithTarget() {
        return issues.isEmpty()
                && sourceMessages >= 0
                && sourceRoomDeletionEvents >= 0
                && sourceMessages == sourceMessageRows.size()
                && sourceRoomDeletionEvents == sourceDeletionEventRows.size();
    }
}
