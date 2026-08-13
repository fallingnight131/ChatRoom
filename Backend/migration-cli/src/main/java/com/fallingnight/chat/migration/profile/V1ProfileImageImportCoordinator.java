package com.fallingnight.chat.migration.profile;

import com.fallingnight.chat.application.profile.ProfileImageObjectWritePort;
import com.fallingnight.chat.persistence.postgres.migration.*;
import java.nio.file.Path;
import java.util.Objects;
import javax.sql.DataSource;

/** Ordered offline orchestration; never used by the product runtime. */
public final class V1ProfileImageImportCoordinator {
    private final DataSource dataSource;
    private final ProfileImageObjectWritePort writer;

    public V1ProfileImageImportCoordinator(DataSource dataSource,
            ProfileImageObjectWritePort writer) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public Report apply(Path directory, VerifiedV1IdentityBackup proof,
            String expectedManifestSha256) {
        var verifier = new V1ProfileImageExportVerifier();
        var before = verifier.verify(directory, proof, expectedManifestSha256);
        var preview = new PostgresV1ProfileImageImportPlanner(dataSource)
                .preview(before.importPlan());
        if (!preview.readyForProviderWrites() && !preview.exactRetryCandidate())
            throw new V1ProfileImageImportException(
                    "profile image import target preview is blocked");
        var uploaded = new V1ProfileImageObjectUploader(writer).upload(before);
        // Re-read the manifest and every object after provider I/O. The verifier
        // binds both passes to the same externally retained manifest hash and
        // backup proof; byte-array-bearing records must not be reference-compared.
        verifier.verify(directory, proof, expectedManifestSha256);
        var applied = new PostgresV1ProfileImageImporter(dataSource)
                .apply(uploaded.input());
        return new Report(preview, uploaded, applied);
    }

    public record Report(V1ProfileImageImportPreview preview,
            V1ProfileImageObjectUploadReport upload,
            V1ProfileImageImportApplyReport apply) {
        public Report {
            Objects.requireNonNull(preview, "preview");
            Objects.requireNonNull(upload, "upload");
            Objects.requireNonNull(apply, "apply");
        }
    }
}
