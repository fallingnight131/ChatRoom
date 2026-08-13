package com.fallingnight.chat.migration.profile;

import com.fallingnight.chat.persistence.postgres.migration.ProviderVerifiedV1ProfileImageImportInput;
import java.util.Objects;

public record V1ProfileImageObjectUploadReport(
        ProviderVerifiedV1ProfileImageImportInput input,
        int uniqueObjects, int created, int alreadyPresent) {
    public V1ProfileImageObjectUploadReport {
        Objects.requireNonNull(input, "input");
        if (uniqueObjects < 0 || created < 0 || alreadyPresent < 0
                || created + alreadyPresent != uniqueObjects)
            throw new IllegalArgumentException("invalid profile image upload report");
    }
}
