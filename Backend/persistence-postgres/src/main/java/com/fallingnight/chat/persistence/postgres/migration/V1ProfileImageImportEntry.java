package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import java.time.Instant;
import java.util.Objects;

/** One proof-bound historical account/room avatar target. */
public record V1ProfileImageImportEntry(Kind kind, long legacyId,
        ProfileImageObjectEvidence object, int width, int height, Instant updatedAt) {
    public V1ProfileImageImportEntry {
        Objects.requireNonNull(kind, "kind");
        if (legacyId <= 0) throw new IllegalArgumentException("invalid legacy avatar target");
        if (object == null) {
            if (width != 0 || height != 0 || updatedAt != null)
                throw new IllegalArgumentException("invalid absent avatar import entry");
        } else if (width < 1 || width > 1024 || height < 1 || height > 1024
                || updatedAt == null) {
            throw new IllegalArgumentException("invalid present avatar import entry");
        }
    }

    public boolean present() { return object != null; }
    public enum Kind { ACCOUNT, ROOM }
}
