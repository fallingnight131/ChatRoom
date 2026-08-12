package com.fallingnight.chat.persistence.postgres.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Re-verifies V1 message cursor state against the protected whole-file backup. */
public final class V1MessageStateImportInputVerifier {
    public VerifiedV1MessageStateImportInput verify(
            Path currentSource,
            Path backupArtifact,
            VerifiedV1IdentityBackup proof) {
        Objects.requireNonNull(proof, "proof");
        requireBackupArtifact(backupArtifact, proof);
        V1MessageStateImportPlan sourcePlan =
                new V1SqliteMessageStateSource(currentSource).readPlan();
        V1MessageStateImportPlan backupPlan =
                new V1SqliteMessageStateSource(backupArtifact).readPlan();
        if (!sourcePlan.readyToCompareWithTarget()
                || !backupPlan.readyToCompareWithTarget()
                || !sourcePlan.equals(backupPlan)) {
            throw new V1MessageStateSourceException(
                    "V1 message state source and backup do not reconcile");
        }
        return new VerifiedV1MessageStateImportInput(
                sourcePlan, proof, currentSource, backupArtifact);
    }

    private static void requireBackupArtifact(
            Path backupArtifact, VerifiedV1IdentityBackup proof) {
        try {
            if (Files.size(backupArtifact) != proof.backupBytes()
                    || !V1SqliteIdentityBackup.sha256(backupArtifact).equals(
                            proof.backupFileSha256())) {
                throw new V1MessageStateSourceException(
                        "V1 message state backup artifact does not match its proof");
            }
        } catch (IOException exception) {
            throw new V1MessageStateSourceException(
                    "V1 message state backup artifact is not readable", exception);
        }
    }
}
