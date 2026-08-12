package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Non-secret target comparison or committed V1 pending-request apply result. */
public record V1ContactRequestImportReport(
        String sourceFingerprint,
        int sourceRequests,
        int sourcePendingRequests,
        int sourceTerminalRequests,
        int insertablePendingRequests,
        int alreadyImportedPendingRequests,
        List<V1ContactRequestImportIssue> issues,
        boolean applied,
        boolean reconciled,
        UUID importRunId) {
    public V1ContactRequestImportReport {
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
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
