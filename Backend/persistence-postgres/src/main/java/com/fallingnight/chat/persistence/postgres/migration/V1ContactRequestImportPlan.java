package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;

/** Deterministic contact-request source plan; only pending rows become target writes. */
public record V1ContactRequestImportPlan(
        String sourceFingerprint,
        int sourceRows,
        int sourcePendingRows,
        int sourceTerminalRows,
        List<PlannedV1ContactRequest> pendingRequests,
        List<V1ContactRequestImportIssue> issues) {
    public V1ContactRequestImportPlan {
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        pendingRequests = List.copyOf(pendingRequests);
        issues = List.copyOf(issues);
        if (sourceRows < 0 || sourcePendingRows < 0 || sourceTerminalRows < 0
                || sourcePendingRows + sourceTerminalRows != sourceRows
                || pendingRequests.size() > sourcePendingRows) {
            throw new IllegalArgumentException("contact request source counts are invalid");
        }
    }

    public boolean readyToCompareWithTarget() {
        return issues.isEmpty() && pendingRequests.size() == sourcePendingRows;
    }
}
