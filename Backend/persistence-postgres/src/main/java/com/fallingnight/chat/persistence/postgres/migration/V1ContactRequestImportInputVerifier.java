package com.fallingnight.chat.persistence.postgres.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Re-verifies contact facts against the protected whole-file V1 backup. */
public final class V1ContactRequestImportInputVerifier {
    public VerifiedV1ContactRequestImportInput verify(
            Path currentSource,
            Path backupArtifact,
            VerifiedV1IdentityBackup proof) {
        Objects.requireNonNull(proof, "proof");
        requireBackupArtifact(backupArtifact, proof);
        V1ContactRequestImportPlan sourcePlan =
                new V1SqliteContactRequestSource(currentSource).readPlan();
        V1ContactRequestImportPlan backupPlan =
                new V1SqliteContactRequestSource(backupArtifact).readPlan();
        if (!sourcePlan.readyToCompareWithTarget()
                || !backupPlan.readyToCompareWithTarget()
                || !sourcePlan.equals(backupPlan)) {
            throw new V1ContactRequestSourceException(
                    "V1 contact request source and backup do not reconcile");
        }
        return new VerifiedV1ContactRequestImportInput(
                sourcePlan, proof, currentSource, backupArtifact);
    }

    private static void requireBackupArtifact(
            Path backupArtifact, VerifiedV1IdentityBackup proof) {
        try {
            if (Files.size(backupArtifact) != proof.backupBytes()
                    || !V1SqliteIdentityBackup.sha256(backupArtifact).equals(
                            proof.backupFileSha256())) {
                throw new V1ContactRequestSourceException(
                        "V1 contact request backup artifact does not match its proof");
            }
        } catch (IOException exception) {
            throw new V1ContactRequestSourceException(
                    "V1 contact request backup artifact is not readable", exception);
        }
    }
}
