package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;

/** Deterministic pre-write plan for V1 sequence high watermarks and read cursors. */
public record V1MessageStateImportPlan(
        List<PlannedV1ConversationCursor> conversationCursors,
        List<PlannedV1MemberReadCursor> memberReadCursors,
        List<V1MessageStateImportIssue> issues) {
    public V1MessageStateImportPlan {
        conversationCursors = List.copyOf(conversationCursors);
        memberReadCursors = List.copyOf(memberReadCursors);
        issues = List.copyOf(issues);
    }

    public boolean readyToCompareWithTarget() {
        return issues.isEmpty();
    }
}
