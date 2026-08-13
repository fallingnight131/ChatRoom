package com.fallingnight.chat.migration.profile;

import java.nio.file.Path;
import java.util.Objects;

public record V1ProfileImageExportReport(Path destination,
        String backupFileSha256, String manifestSha256, int entries,
        int present, int absent, int invalid, int uniqueObjects) {
    public V1ProfileImageExportReport {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(backupFileSha256, "backupFileSha256");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        if (!backupFileSha256.matches("[0-9a-f]{64}")
                || !manifestSha256.matches("[0-9a-f]{64}")
                || entries < 0 || present < 0 || absent < 0 || invalid < 0
                || uniqueObjects < 0 || present + absent + invalid != entries
                || uniqueObjects > present)
            throw new IllegalArgumentException("invalid profile image export report");
    }
    public boolean readyToImport() { return invalid == 0; }
}
