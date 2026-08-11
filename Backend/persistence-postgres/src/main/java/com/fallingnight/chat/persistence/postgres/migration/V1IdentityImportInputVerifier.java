package com.fallingnight.chat.persistence.postgres.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Re-verifies the live source and protected backup immediately before target apply. */
public final class V1IdentityImportInputVerifier {
    public VerifiedV1IdentityImportInput verify(
            Path currentSource,
            Path backupArtifact,
            VerifiedV1IdentityBackup proof) {
        Objects.requireNonNull(proof, "proof");
        V1IdentityImportPlan sourcePlan = new V1SqliteIdentitySource(currentSource).readPlan();
        V1IdentityImportPlan backupPlan = new V1SqliteIdentitySource(backupArtifact).readPlan();
        if (!sourcePlan.readyToCompareWithTarget()
                || !backupPlan.readyToCompareWithTarget()
                || !sourcePlan.equals(backupPlan)
                || !sourcePlan.sourceFingerprintSha256().equals(
                        proof.sourceFingerprintSha256())
                || sourcePlan.sourceRows() != proof.identityRows()) {
            throw new V1IdentitySourceException(
                    "V1 identity import source, backup, and proof do not reconcile");
        }
        try {
            if (Files.size(backupArtifact) != proof.backupBytes()
                    || !V1SqliteIdentityBackup.sha256(backupArtifact).equals(
                            proof.backupFileSha256())) {
                throw new V1IdentitySourceException(
                        "V1 identity backup artifact does not match its proof");
            }
        } catch (IOException exception) {
            throw new V1IdentitySourceException(
                    "V1 identity backup artifact is not readable", exception);
        }
        return new VerifiedV1IdentityImportInput(
                sourcePlan, proof, currentSource, backupArtifact);
    }
}
