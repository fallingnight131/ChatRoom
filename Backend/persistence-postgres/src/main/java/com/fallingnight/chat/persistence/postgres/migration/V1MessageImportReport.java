package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.UUID;

/** Non-secret target preview or committed V1 message import outcome. */
public record V1MessageImportReport(
        String stateFingerprintSha256,
        String payloadFingerprintSha256,
        int sourceMessages,
        int sourceEntries,
        int sourceLegacyDevices,
        int sourceReadCursors,
        int insertableMessages,
        int alreadyImportedMessages,
        int insertableEntries,
        int alreadyImportedEntries,
        int insertableLegacyDevices,
        int alreadyImportedLegacyDevices,
        int readCursorsToUpdate,
        int alreadyTranslatedReadCursors,
        List<V1MessageTargetIssue> issues,
        boolean applied,
        boolean reconciled,
        UUID importRunId) {
    public V1MessageImportReport {
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
