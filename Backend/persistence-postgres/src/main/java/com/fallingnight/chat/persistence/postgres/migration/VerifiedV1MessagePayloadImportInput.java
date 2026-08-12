package com.fallingnight.chat.persistence.postgres.migration;

import java.nio.file.Path;
import java.util.Objects;

/** Capability proving exact V1 payload source/backup/physical-proof reconciliation. */
public final class VerifiedV1MessagePayloadImportInput {
    private final V1MessagePayloadImportPlan plan;
    private final VerifiedV1IdentityBackup backupProof;
    private final Path source;
    private final Path backup;

    VerifiedV1MessagePayloadImportInput(
            V1MessagePayloadImportPlan plan,
            VerifiedV1IdentityBackup backupProof,
            Path source,
            Path backup) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.backupProof = Objects.requireNonNull(backupProof, "backupProof");
        this.source = Objects.requireNonNull(source, "source");
        this.backup = Objects.requireNonNull(backup, "backup");
    }

    public V1MessagePayloadImportPlan plan() {
        return plan;
    }

    public VerifiedV1IdentityBackup backupProof() {
        return backupProof;
    }

    void reverify() {
        VerifiedV1MessagePayloadImportInput current = new V1MessagePayloadImportInputVerifier()
                .verify(source, backup, backupProof);
        if (!plan.equals(current.plan)) {
            throw new V1MessagePayloadSourceException(
                    "V1 message payload source changed during target apply");
        }
    }
}
