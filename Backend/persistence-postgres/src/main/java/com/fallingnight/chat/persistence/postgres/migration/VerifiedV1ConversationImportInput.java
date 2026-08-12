package com.fallingnight.chat.persistence.postgres.migration;

import java.nio.file.Path;
import java.util.Objects;

/** In-memory conversation-import capability after source/backup/proof reconciliation. */
public final class VerifiedV1ConversationImportInput {
    private final V1ConversationImportPlan plan;
    private final VerifiedV1IdentityBackup backupProof;
    private final Path source;
    private final Path backup;

    VerifiedV1ConversationImportInput(
            V1ConversationImportPlan plan,
            VerifiedV1IdentityBackup backupProof,
            Path source,
            Path backup) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.backupProof = Objects.requireNonNull(backupProof, "backupProof");
        this.source = Objects.requireNonNull(source, "source");
        this.backup = Objects.requireNonNull(backup, "backup");
    }

    public V1ConversationImportPlan plan() {
        return plan;
    }

    public VerifiedV1IdentityBackup backupProof() {
        return backupProof;
    }

    void reverify() {
        VerifiedV1ConversationImportInput current = new V1ConversationImportInputVerifier()
                .verify(source, backup, backupProof);
        if (!plan.equals(current.plan)) {
            throw new V1ConversationSourceException(
                    "V1 conversation source changed during target apply");
        }
    }
}
