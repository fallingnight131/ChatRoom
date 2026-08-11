package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;

/** Immutable pre-write plan and safe validation report. */
public record V1IdentityImportPlan(
        String sourceFingerprintSha256,
        int sourceRows,
        List<PlannedIdentityAccount> accounts,
        List<IdentityImportIssue> issues) {
    public V1IdentityImportPlan {
        Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        accounts = List.copyOf(accounts);
        issues = List.copyOf(issues);
    }

    public boolean readyToCompareWithTarget() {
        return issues.isEmpty() && accounts.size() == sourceRows;
    }
}
