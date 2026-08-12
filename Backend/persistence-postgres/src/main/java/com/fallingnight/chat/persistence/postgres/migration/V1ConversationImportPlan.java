package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;

/** Deterministic, pre-write V1 conversation plan and safe blocking report. */
public record V1ConversationImportPlan(
        String sourceFingerprintSha256,
        int sourceRooms,
        int sourceFriendships,
        List<PlannedV1Conversation> conversations,
        List<PlannedV1ConversationMember> memberships,
        List<V1ConversationImportIssue> issues) {
    public V1ConversationImportPlan {
        Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        conversations = List.copyOf(conversations);
        memberships = List.copyOf(memberships);
        issues = List.copyOf(issues);
    }

    public boolean readyToCompareWithTarget() {
        return issues.isEmpty() && conversations.size() == sourceRooms + sourceFriendships;
    }
}
