package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Non-secret target comparison or committed V1 conversation apply result. */
public record V1ConversationImportReport(
        String sourceFingerprintSha256,
        int sourceConversations,
        int sourceMemberships,
        int insertableConversations,
        int alreadyImportedConversations,
        int insertableMemberships,
        int alreadyImportedMemberships,
        List<V1ConversationImportIssue> issues,
        boolean applied,
        boolean reconciled,
        UUID importRunId) {
    public V1ConversationImportReport {
        Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        issues = List.copyOf(issues);
        if (!applied && importRunId != null) {
            throw new IllegalArgumentException("a preview cannot contain an import run");
        }
        if (applied && (!reconciled || importRunId == null)) {
            throw new IllegalArgumentException("an apply must be reconciled and audited");
        }
    }

    public boolean readyToApply() {
        return issues.isEmpty();
    }
}
