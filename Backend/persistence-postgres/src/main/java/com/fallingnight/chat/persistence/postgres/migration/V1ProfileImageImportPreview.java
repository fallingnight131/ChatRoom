package com.fallingnight.chat.persistence.postgres.migration;

import java.util.List;
import java.util.Objects;

/** Read-only PostgreSQL comparison performed before object-provider writes. */
public record V1ProfileImageImportPreview(String manifestSha256, int entries,
        int present, int absent, int uniqueObjects, int objectsAlreadyRegistered,
        int providerObjectsToVerify, List<V1ProfileImageImportIssue> issues) {
    public V1ProfileImageImportPreview {
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(issues, "issues"); issues = List.copyOf(issues);
        if (!manifestSha256.matches("[0-9a-f]{64}") || entries < 0 || present < 0
                || absent < 0 || uniqueObjects < 0 || objectsAlreadyRegistered < 0
                || providerObjectsToVerify != uniqueObjects || present + absent != entries
                || objectsAlreadyRegistered > uniqueObjects)
            throw new IllegalArgumentException("invalid avatar import preview");
    }
    public boolean readyForProviderWrites() { return issues.isEmpty(); }
}
