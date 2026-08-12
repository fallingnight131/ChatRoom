package com.fallingnight.chat.persistence.postgres.migration;

import java.nio.file.Path;
import java.util.Objects;

/** Re-verifiable capability binding source, backup proof, and object evidence. */
public final class VerifiedV1AttachmentImportInput {
    private final V1AttachmentImportPlan plan;
    private final VerifiedV1IdentityBackup backupProof;
    private final V1AttachmentObjectEvidenceBundle evidence;
    private final Path source;
    private final Path backup;

    VerifiedV1AttachmentImportInput(
            V1AttachmentImportPlan plan,
            VerifiedV1IdentityBackup backupProof,
            V1AttachmentObjectEvidenceBundle evidence,
            Path source,
            Path backup) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.backupProof = Objects.requireNonNull(backupProof, "backupProof");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.source = Objects.requireNonNull(source, "source");
        this.backup = Objects.requireNonNull(backup, "backup");
    }

    public V1AttachmentImportPlan plan() {
        return plan;
    }

    public VerifiedV1IdentityBackup backupProof() {
        return backupProof;
    }

    public V1AttachmentObjectEvidenceBundle evidence() {
        return evidence;
    }

    void reverify() {
        VerifiedV1AttachmentImportInput current = new V1AttachmentImportInputVerifier()
                .verify(source, backup, backupProof, evidence);
        if (!samePlan(plan, current.plan)) {
            throw new V1AttachmentImportException(
                    "V1 attachment source or object evidence changed during target apply");
        }
    }

    private static boolean samePlan(V1AttachmentImportPlan expected,
            V1AttachmentImportPlan actual) {
        return expected.sourceFingerprintSha256().equals(actual.sourceFingerprintSha256())
                && expected.evidenceFingerprintSha256()
                        .equals(actual.evidenceFingerprintSha256())
                && expected.sourceAttachments() == actual.sourceAttachments()
                && expected.suppliedObjectEvidence() == actual.suppliedObjectEvidence()
                && expected.attachments().size() == actual.attachments().size()
                && expected.issues().equals(actual.issues());
    }
}
