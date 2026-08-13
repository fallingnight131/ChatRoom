package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Import capability produced only after every unique provider object is exact. */
public final class ProviderVerifiedV1ProfileImageImportInput {
    private final V1ProfileImageImportPlan plan;

    private ProviderVerifiedV1ProfileImageImportInput(V1ProfileImageImportPlan plan) {
        this.plan = plan;
    }

    public static ProviderVerifiedV1ProfileImageImportInput confirm(
            V1ProfileImageImportPlan plan, List<ProfileImageObjectEvidence> confirmed) {
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(confirmed, "confirmed");
        Map<String, ProfileImageObjectEvidence> expected = new HashMap<>();
        for (V1ProfileImageImportEntry entry : plan.entries()) if (entry.present())
            expected.putIfAbsent(entry.object().objectKey(), entry.object());
        Map<String, ProfileImageObjectEvidence> actual = new HashMap<>();
        for (ProfileImageObjectEvidence evidence : confirmed) {
            Objects.requireNonNull(evidence, "confirmed evidence");
            if (actual.put(evidence.objectKey(), evidence) != null)
                throw new IllegalArgumentException("duplicate provider object evidence");
        }
        if (!actual.keySet().equals(expected.keySet()))
            throw new IllegalArgumentException("provider object evidence set is incomplete");
        for (Map.Entry<String, ProfileImageObjectEvidence> entry : expected.entrySet())
            if (!exact(entry.getValue(), actual.get(entry.getKey())))
                throw new IllegalArgumentException("provider object evidence does not match plan");
        return new ProviderVerifiedV1ProfileImageImportInput(plan);
    }

    public V1ProfileImageImportPlan plan() { return plan; }

    private static boolean exact(ProfileImageObjectEvidence left,
            ProfileImageObjectEvidence right) {
        return left.objectKey().equals(right.objectKey())
                && left.byteSize() == right.byteSize()
                && left.mediaType().equals(right.mediaType())
                && MessageDigest.isEqual(left.contentSha256(), right.contentSha256());
    }
}
