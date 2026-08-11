package com.fallingnight.chat.persistence.postgres.migration;

import java.nio.file.Path;
import java.util.Objects;

/** In-memory apply capability produced only after source, backup, and proof reconciliation. */
public final class VerifiedV1IdentityImportInput {
    private final V1IdentityImportPlan plan;
    private final VerifiedV1IdentityBackup backupProof;
    private final Path source;
    private final Path backup;

    VerifiedV1IdentityImportInput(
            V1IdentityImportPlan plan,
            VerifiedV1IdentityBackup backupProof,
            Path source,
            Path backup) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.backupProof = Objects.requireNonNull(backupProof, "backupProof");
        this.source = Objects.requireNonNull(source, "source");
        this.backup = Objects.requireNonNull(backup, "backup");
    }

    public V1IdentityImportPlan plan() {
        return plan;
    }

    public VerifiedV1IdentityBackup backupProof() {
        return backupProof;
    }

    void reverify() {
        VerifiedV1IdentityImportInput current = new V1IdentityImportInputVerifier()
                .verify(source, backup, backupProof);
        if (!plan.equals(current.plan)) {
            throw new V1IdentitySourceException(
                    "V1 identity source changed during target apply");
        }
    }
}
