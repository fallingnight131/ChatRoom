package com.fallingnight.chat.persistence.postgres.migration;

import java.util.Objects;
import java.util.UUID;

/** Atomic PostgreSQL apply or exact-retry result. */
public record V1ProfileImageImportApplyReport(String manifestSha256,
        int entries, int present, int absent, int uniqueObjects,
        int insertedPointers, boolean alreadyApplied, UUID importRunId) {
    public V1ProfileImageImportApplyReport {
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(importRunId, "importRunId");
        if (!manifestSha256.matches("[0-9a-f]{64}") || entries < 1 || present < 0
                || absent < 0 || uniqueObjects < 0 || insertedPointers < 0
                || present + absent != entries || uniqueObjects > present
                || insertedPointers > present || alreadyApplied && insertedPointers != 0)
            throw new IllegalArgumentException("invalid profile image import apply report");
    }
}
