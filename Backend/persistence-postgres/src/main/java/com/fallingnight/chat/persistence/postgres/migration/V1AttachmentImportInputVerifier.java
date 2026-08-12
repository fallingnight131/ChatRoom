package com.fallingnight.chat.persistence.postgres.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Reconciles one attachment graph and evidence manifest with a protected backup. */
public final class V1AttachmentImportInputVerifier {
    public VerifiedV1AttachmentImportInput verify(
            Path currentSource,
            Path backupArtifact,
            VerifiedV1IdentityBackup proof,
            V1AttachmentObjectEvidenceBundle evidence) {
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(evidence, "evidence");
        requireBackupArtifact(backupArtifact, proof);
        V1AttachmentSourcePlan sourcePlan =
                new V1SqliteAttachmentSource(currentSource).readPlan();
        V1AttachmentSourcePlan backupPlan =
                new V1SqliteAttachmentSource(backupArtifact).readPlan();
        if (!sourcePlan.readyForObjectEvidence()
                || !backupPlan.readyForObjectEvidence()
                || !sourcePlan.equals(backupPlan)) {
            throw new V1AttachmentImportException(
                    "V1 attachment source and backup do not reconcile");
        }
        V1AttachmentImportPlan plan =
                new V1AttachmentEvidencePlanner().plan(sourcePlan, evidence);
        if (!plan.readyToCompareWithTarget()) {
            throw new V1AttachmentImportException(
                    "V1 attachment object evidence does not reconcile");
        }
        return new VerifiedV1AttachmentImportInput(
                plan, proof, evidence, currentSource, backupArtifact);
    }

    private static void requireBackupArtifact(
            Path backupArtifact, VerifiedV1IdentityBackup proof) {
        try {
            if (Files.size(backupArtifact) != proof.backupBytes()
                    || !V1SqliteIdentityBackup.sha256(backupArtifact).equals(
                            proof.backupFileSha256())) {
                throw new V1AttachmentImportException(
                        "V1 attachment backup artifact does not match its proof");
            }
        } catch (IOException exception) {
            throw new V1AttachmentImportException(
                    "V1 attachment backup artifact is not readable", exception);
        }
    }
}
