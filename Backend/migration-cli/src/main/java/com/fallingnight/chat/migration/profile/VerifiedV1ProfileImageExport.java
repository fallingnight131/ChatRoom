package com.fallingnight.chat.migration.profile;

import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import com.fallingnight.chat.persistence.postgres.migration.V1ProfileImageImportEntry;
import com.fallingnight.chat.persistence.postgres.migration.V1ProfileImageImportPlan;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Strictly verified, proof-bound profile-image import input. */
public record VerifiedV1ProfileImageExport(Path directory, String manifestSha256,
        String backupFileSha256, String identityFingerprintSha256,
        List<Entry> entries, int uniqueObjects) {
    public VerifiedV1ProfileImageExport {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(backupFileSha256, "backupFileSha256");
        Objects.requireNonNull(identityFingerprintSha256, "identityFingerprintSha256");
        Objects.requireNonNull(entries, "entries");
        if (!manifestSha256.matches("[0-9a-f]{64}")
                || !backupFileSha256.matches("[0-9a-f]{64}")
                || !identityFingerprintSha256.matches("[0-9a-f]{64}")
                || uniqueObjects < 0)
            throw new IllegalArgumentException("invalid verified profile image export");
        directory = directory.toAbsolutePath().normalize();
        entries = List.copyOf(entries);
    }

    public enum Kind { ACCOUNT, ROOM }

    public V1ProfileImageImportPlan importPlan() {
        return new V1ProfileImageImportPlan(manifestSha256, backupFileSha256,
                identityFingerprintSha256, entries.stream().map(entry ->
                        new V1ProfileImageImportEntry(
                                V1ProfileImageImportEntry.Kind.valueOf(entry.kind().name()),
                                entry.legacyId(), entry.object(), entry.width(), entry.height(),
                                entry.updatedAt())).toList(), uniqueObjects);
    }

    public record Entry(Kind kind, long legacyId,
            ProfileImageObjectEvidence object, int width, int height,
            Instant updatedAt) {
        public Entry {
            Objects.requireNonNull(kind, "kind");
            if (legacyId <= 0)
                throw new IllegalArgumentException("invalid legacy profile target ID");
            if (object == null) {
                if (width != 0 || height != 0 || updatedAt != null)
                    throw new IllegalArgumentException("invalid absent profile image entry");
            } else if (width < 1 || height < 1 || updatedAt == null) {
                throw new IllegalArgumentException("invalid present profile image entry");
            }
        }

        public boolean present() { return object != null; }
    }
}
