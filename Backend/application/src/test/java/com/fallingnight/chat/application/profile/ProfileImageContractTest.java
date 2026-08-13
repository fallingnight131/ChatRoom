package com.fallingnight.chat.application.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ProfileImageContractTest {
    @Test void ownsAndClearsLegacyUploadCopies() {
        byte[] source = {1, 2, 3};
        LegacyV1AvatarUpload upload = LegacyV1AvatarUpload.copyOf(source);
        source[0] = 9;
        assertEquals(Integer.valueOf(1), upload.withCopy(bytes -> (int) bytes[0]));
        upload.close(); upload.close();
        assertThrows(IllegalStateException.class, upload::byteSize);
        assertThrows(IllegalStateException.class, () -> upload.withCopy(bytes -> bytes.length));
        assertThrows(IllegalArgumentException.class,
                () -> LegacyV1AvatarUpload.copyOf(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> LegacyV1AvatarUpload.copyOf(
                        new byte[LegacyV1AvatarUpload.MAX_BYTES + 1]));
    }

    @Test void acceptsOnlyDigestMatchedBoundedCanonicalPng() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(png);
        CanonicalProfileImage image = new CanonicalProfileImage(png, 256, 256, digest);
        png[8] = 2; digest[0] ^= 1;
        assertEquals(1, image.pngBytes()[8]);
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalProfileImage(image.pngBytes(), 0, 256,
                        image.contentSha256()));
        byte[] wrong = image.contentSha256(); wrong[0] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalProfileImage(image.pngBytes(), 256, 256, wrong));
        byte[] notPng = image.pngBytes(); Arrays.fill(notPng, 0, 8, (byte) 0);
        assertThrows(IllegalArgumentException.class,
                () -> new CanonicalProfileImage(notPng, 256, 256,
                        MessageDigest.getInstance("SHA-256").digest(notPng)));
    }

    @Test void objectEvidenceIsPrivateBoundedAndDefensive() {
        byte[] digest = new byte[32];
        ProfileImageObjectEvidence evidence = new ProfileImageObjectEvidence(
                "avatars/sha256/00.png", 9, digest, "image/png");
        digest[0] = 1; assertEquals(0, evidence.contentSha256()[0]);
        assertThrows(IllegalArgumentException.class, () -> new ProfileImageObjectEvidence(
                "attachments/00.png", 9, new byte[32], "image/png"));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImageObjectEvidence(
                "avatars/00.jpg", 9, new byte[32], "image/jpeg"));
    }
}
