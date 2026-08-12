package com.fallingnight.chat.persistence.postgres.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Re-verifies V1 conversation graph and the existing whole-file backup proof. */
public final class V1ConversationImportInputVerifier {
    public VerifiedV1ConversationImportInput verify(
            Path currentSource,
            Path backupArtifact,
            VerifiedV1IdentityBackup proof) {
        Objects.requireNonNull(proof, "proof");
        requireBackupArtifact(backupArtifact, proof);
        V1ConversationImportPlan sourcePlan =
                new V1SqliteConversationSource(currentSource).readPlan();
        V1ConversationImportPlan backupPlan =
                new V1SqliteConversationSource(backupArtifact).readPlan();
        if (!sourcePlan.readyToCompareWithTarget()
                || !backupPlan.readyToCompareWithTarget()
                || !sourcePlan.equals(backupPlan)) {
            throw new V1ConversationSourceException(
                    "V1 conversation source and backup do not reconcile");
        }
        return new VerifiedV1ConversationImportInput(
                sourcePlan, proof, currentSource, backupArtifact);
    }

    private static void requireBackupArtifact(
            Path backupArtifact, VerifiedV1IdentityBackup proof) {
        try {
            if (Files.size(backupArtifact) != proof.backupBytes()
                    || !V1SqliteIdentityBackup.sha256(backupArtifact).equals(
                            proof.backupFileSha256())) {
                throw new V1ConversationSourceException(
                        "V1 conversation backup artifact does not match its proof");
            }
        } catch (IOException exception) {
            throw new V1ConversationSourceException(
                    "V1 conversation backup artifact is not readable", exception);
        }
    }
}
