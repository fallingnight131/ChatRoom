package com.fallingnight.chat.application.profile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProfileImageCleanupClaim(UUID claimId, String objectKey, Instant claimedAt) {
    public ProfileImageCleanupClaim {
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(claimedAt, "claimedAt");
        String prefix = "avatars/sha256/";
        String digest = objectKey.length() == prefix.length() + 64 + 4
                ? objectKey.substring(prefix.length(), prefix.length() + 64) : "";
        if (!objectKey.startsWith("avatars/sha256/") || !objectKey.endsWith(".png")
                || digest.length() != 64
                || !digest.chars().allMatch(value -> value >= '0' && value <= '9'
                    || value >= 'a' && value <= 'f')
                || objectKey.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid profile image cleanup object key");
    }
}
