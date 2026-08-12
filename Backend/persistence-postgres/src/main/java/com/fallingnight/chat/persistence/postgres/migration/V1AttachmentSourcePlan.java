package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;

/** Deterministic file/message graph plan; no locator survives into candidates or issues. */
public record V1AttachmentSourcePlan(
        String sourceFingerprintSha256,
        int sourceFiles,
        int sourceMessageLinks,
        List<PlannedV1AttachmentSource> attachments,
        List<V1AttachmentSourceIssue> issues) {
    public V1AttachmentSourcePlan {
        Objects.requireNonNull(sourceFingerprintSha256, "sourceFingerprintSha256");
        attachments = List.copyOf(attachments);
        issues = List.copyOf(issues);
    }
    public boolean readyForObjectEvidence() {
        return issues.isEmpty() && attachments.size() == sourceFiles
                && attachments.size() == sourceMessageLinks;
    }
}
