package com.fallingnight.chat.application.profile;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Verified immutable object evidence; never a public authorization URL. */
public record ProfileImageObjectEvidence(String objectKey, long byteSize,
        byte[] contentSha256, String mediaType) {
    public ProfileImageObjectEvidence {
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(mediaType, "mediaType");
        String expectedKey = "avatars/sha256/"
                + HexFormat.of().formatHex(contentSha256) + ".png";
        if (!objectKey.equals(expectedKey)
                || objectKey.codePoints().anyMatch(Character::isISOControl)
                || byteSize < 1 || byteSize > LegacyV1AvatarUpload.MAX_BYTES
                || contentSha256.length != 32 || !"image/png".equals(mediaType))
            throw new IllegalArgumentException("invalid profile image object evidence");
        contentSha256 = Arrays.copyOf(contentSha256, contentSha256.length);
    }
    @Override public byte[] contentSha256() {
        return Arrays.copyOf(contentSha256, contentSha256.length);
    }

    public static String objectKey(byte[] contentSha256) {
        Objects.requireNonNull(contentSha256, "contentSha256");
        if (contentSha256.length != 32)
            throw new IllegalArgumentException("contentSha256 must contain 32 bytes");
        return "avatars/sha256/" + HexFormat.of().formatHex(contentSha256) + ".png";
    }
}
