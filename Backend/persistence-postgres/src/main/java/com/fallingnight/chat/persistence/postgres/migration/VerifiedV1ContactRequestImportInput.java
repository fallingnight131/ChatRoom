package com.fallingnight.chat.persistence.postgres.migration;

import java.nio.file.Path;
import java.util.Objects;

/** Capability issued only after source/backup/proof contact-plan reconciliation. */
public final class VerifiedV1ContactRequestImportInput {
    private final V1ContactRequestImportPlan plan;
    private final VerifiedV1IdentityBackup backupProof;
    private final Path source;
    private final Path backup;

    VerifiedV1ContactRequestImportInput(
            V1ContactRequestImportPlan plan,
            VerifiedV1IdentityBackup backupProof,
            Path source,
            Path backup) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.backupProof = Objects.requireNonNull(backupProof, "backupProof");
        this.source = Objects.requireNonNull(source, "source");
        this.backup = Objects.requireNonNull(backup, "backup");
    }

    public V1ContactRequestImportPlan plan() { return plan; }
    public VerifiedV1IdentityBackup backupProof() { return backupProof; }

    void reverify() {
        VerifiedV1ContactRequestImportInput current = new V1ContactRequestImportInputVerifier()
                .verify(source, backup, backupProof);
        if (!plan.equals(current.plan)) {
            throw new V1ContactRequestSourceException(
                    "V1 contact request source changed during target apply");
        }
    }
}
