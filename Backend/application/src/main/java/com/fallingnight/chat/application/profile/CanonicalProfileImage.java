package com.fallingnight.chat.application.profile;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Decoder-produced, re-encoded PNG suitable for private object storage. */
public record CanonicalProfileImage(byte[] pngBytes, int width, int height,
        byte[] contentSha256) {
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    public static final int MAX_DIMENSION = 1024;

    public CanonicalProfileImage {
        Objects.requireNonNull(pngBytes, "pngBytes");
        Objects.requireNonNull(contentSha256, "contentSha256");
        if (pngBytes.length == 0 || pngBytes.length > LegacyV1AvatarUpload.MAX_BYTES
                || width < 1 || width > MAX_DIMENSION
                || height < 1 || height > MAX_DIMENSION
                || contentSha256.length != 32
                || pngBytes.length < PNG_SIGNATURE.length
                || !Arrays.equals(PNG_SIGNATURE,
                    Arrays.copyOf(pngBytes, PNG_SIGNATURE.length))
                || !MessageDigest.isEqual(contentSha256, sha256(pngBytes)))
            throw new IllegalArgumentException("invalid canonical profile image");
        pngBytes = Arrays.copyOf(pngBytes, pngBytes.length);
        contentSha256 = Arrays.copyOf(contentSha256, contentSha256.length);
    }

    @Override public byte[] pngBytes() { return Arrays.copyOf(pngBytes, pngBytes.length); }
    @Override public byte[] contentSha256() {
        return Arrays.copyOf(contentSha256, contentSha256.length);
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
