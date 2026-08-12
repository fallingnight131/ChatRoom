package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;

/** Exact source/evidence reconciliation ready for target preview. */
public record V1AttachmentImportPlan(
        String sourceFingerprintSha256,
        String evidenceFingerprintSha256,
        int sourceAttachments,
        int suppliedObjectEvidence,
        List<PlannedV1AttachmentImport> attachments,
        List<V1AttachmentImportIssue> issues) {
    public V1AttachmentImportPlan {
        Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        Objects.requireNonNull(evidenceFingerprintSha256, "evidenceFingerprintSha256");
        attachments = List.copyOf(attachments);
        issues = List.copyOf(issues);
    }

    public boolean readyToCompareWithTarget() {
        return issues.isEmpty() && attachments.size() == sourceAttachments;
    }
}
