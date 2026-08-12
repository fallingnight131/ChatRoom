package com.fallingnight.chat.persistence.postgres.migration;

import java.nio.file.Path;
import java.util.Objects;

/** In-memory capability proving exact V1 message-state source/backup reconciliation. */
public final class VerifiedV1MessageStateImportInput {
    private final V1MessageStateImportPlan plan;
    private final VerifiedV1IdentityBackup backupProof;
    private final Path source;
    private final Path backup;

    VerifiedV1MessageStateImportInput(
            V1MessageStateImportPlan plan,
            VerifiedV1IdentityBackup backupProof,
            Path source,
            Path backup) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.backupProof = Objects.requireNonNull(backupProof, "backupProof");
        this.source = Objects.requireNonNull(source, "source");
        this.backup = Objects.requireNonNull(backup, "backup");
    }

    public V1MessageStateImportPlan plan() {
        return plan;
    }

    public VerifiedV1IdentityBackup backupProof() {
        return backupProof;
    }

    void reverify() {
        VerifiedV1MessageStateImportInput current = new V1MessageStateImportInputVerifier()
                .verify(source, backup, backupProof);
        if (!plan.equals(current.plan)) {
            throw new V1MessageStateSourceException(
                    "V1 message state source changed during target apply");
        }
    }
}
