package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Non-secret target comparison or committed-apply result. */
public record V1IdentityImportReport(
        String sourceFingerprintSha256,
        int sourceRows,
        int insertableRows,
        int alreadyImportedRows,
        int insertedRows,
        int unexpectedTargetRows,
        List<IdentityImportIssue> issues,
        boolean applied,
        boolean reconciled,
        UUID importRunId) {
    public V1IdentityImportReport {
        Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        issues = List.copyOf(issues);
        if (!applied && (insertedRows != 0 || importRunId != null)) {
            throw new IllegalArgumentException("a preview cannot contain apply results");
        }
        if (applied && (!reconciled || importRunId == null)) {
            throw new IllegalArgumentException("an apply must be reconciled and audited");
        }
    }

    public boolean readyToApply() {
        return issues.isEmpty() && unexpectedTargetRows == 0;
    }
}
