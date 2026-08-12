package com.fallingnight.chat.persistence.postgres.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Exact V1 message payload reconciliation against a protected whole-file backup. */
public final class V1MessagePayloadImportInputVerifier {
    public VerifiedV1MessagePayloadImportInput verify(
            Path currentSource,
            Path backupArtifact,
            VerifiedV1IdentityBackup proof) {
        Objects.requireNonNull(proof, "proof");
        requireBackupArtifact(backupArtifact, proof);
        V1MessagePayloadImportPlan sourcePlan =
                new V1SqliteMessagePayloadSource(currentSource).readPlan();
        V1MessagePayloadImportPlan backupPlan =
                new V1SqliteMessagePayloadSource(backupArtifact).readPlan();
        if (!sourcePlan.readyToCompareWithTarget()
                || !backupPlan.readyToCompareWithTarget()
                || !sourcePlan.equals(backupPlan)) {
            throw new V1MessagePayloadSourceException(
                    "V1 message payload source and backup do not reconcile");
        }
        return new VerifiedV1MessagePayloadImportInput(
                sourcePlan, proof, currentSource, backupArtifact);
    }

    private static void requireBackupArtifact(
            Path backupArtifact, VerifiedV1IdentityBackup proof) {
        try {
            if (Files.size(backupArtifact) != proof.backupBytes()
                    || !V1SqliteIdentityBackup.sha256(backupArtifact).equals(
                            proof.backupFileSha256())) {
                throw new V1MessagePayloadSourceException(
                        "V1 message payload backup artifact does not match its proof");
            }
        } catch (IOException exception) {
            throw new V1MessagePayloadSourceException(
                    "V1 message payload backup artifact is not readable", exception);
        }
    }
}
