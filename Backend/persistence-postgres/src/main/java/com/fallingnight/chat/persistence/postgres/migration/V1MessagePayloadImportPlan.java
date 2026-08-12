package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;

/** Deterministic V1 content conversion plan; any issue blocks target writes. */
public record V1MessagePayloadImportPlan(
        String sourceFingerprintSha256,
        int sourceRows,
        List<PlannedV1MessagePayload> messages,
        List<V1MessagePayloadImportIssue> issues) {
    public V1MessagePayloadImportPlan {
        java.util.Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        messages = List.copyOf(messages);
        issues = List.copyOf(issues);
    }

    public boolean readyToCompareWithTarget() {
        return issues.isEmpty() && sourceRows == messages.size();
    }
}
