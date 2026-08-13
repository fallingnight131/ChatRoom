package com.fallingnight.chat.persistence.postgres.migration;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable manifest projection used for PostgreSQL target comparison. */
public record V1ProfileImageImportPlan(String manifestSha256,
        String backupFileSha256, String identityFingerprintSha256,
        List<V1ProfileImageImportEntry> entries, int uniqueObjects) {
    public V1ProfileImageImportPlan {
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(backupFileSha256, "backupFileSha256");
        Objects.requireNonNull(identityFingerprintSha256, "identityFingerprintSha256");
        Objects.requireNonNull(entries, "entries");
        if (!manifestSha256.matches("[0-9a-f]{64}")
                || !backupFileSha256.matches("[0-9a-f]{64}")
                || !identityFingerprintSha256.matches("[0-9a-f]{64}")
                || uniqueObjects < 0 || entries.isEmpty())
            throw new IllegalArgumentException("invalid avatar import plan evidence");
        entries = List.copyOf(entries);
        Set<String> targets = new HashSet<>(), objects = new HashSet<>();
        V1ProfileImageImportEntry previous = null;
        for (V1ProfileImageImportEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!targets.add(entry.kind() + ":" + entry.legacyId()))
                throw new IllegalArgumentException("duplicate avatar import target");
            if (previous != null && (entry.kind().ordinal() < previous.kind().ordinal()
                    || entry.kind() == previous.kind()
                    && entry.legacyId() <= previous.legacyId()))
                throw new IllegalArgumentException("avatar import targets are not ordered");
            previous = entry;
            if (entry.present()) objects.add(entry.object().objectKey());
        }
        if (objects.size() != uniqueObjects)
            throw new IllegalArgumentException("avatar import object count does not reconcile");
    }

    public int presentEntries() {
        return Math.toIntExact(entries.stream().filter(
                V1ProfileImageImportEntry::present).count());
    }
}
