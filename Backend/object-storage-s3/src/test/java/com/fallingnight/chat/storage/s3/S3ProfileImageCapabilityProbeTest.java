package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.profile.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class S3ProfileImageCapabilityProbeTest {
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @Test void provesFreshPutExactRetryReadAndVerifiedCleanup() throws Exception {
        FakeObjects objects = new FakeObjects();
        var report = new S3ProfileImageCapabilityProbe(
                objects, objects, objects, S3ProfileImageCapabilityProbeTest::image).run();
        assertEquals(new S3ProfileImageCapabilityProbe.CapabilityReport(
                true, true, true, true), report);
        assertEquals(2, objects.writeCalls); assertEquals(2, objects.readCalls);
        assertEquals(1, objects.deleteCalls); assertNull(objects.bytes);
    }

    @Test void alwaysCleansAfterRetryOrReadFailureAndPreservesCleanupFailure() {
        FakeObjects retry = new FakeObjects(); retry.repeatClaimsCreated = true;
        var retryFailure = assertThrows(ProfileImageObjectStoreCapabilityProbeException.class,
                () -> probe(retry).run());
        assertTrue(retryFailure.getMessage().contains("converge create-only retry"));
        assertEquals(1, retry.deleteCalls);

        FakeObjects cleanup = new FakeObjects(); cleanup.repeatClaimsCreated = true;
        cleanup.deleteFails = true;
        var cleanupFailure = assertThrows(ProfileImageObjectStoreCapabilityProbeException.class,
                () -> probe(cleanup).run());
        assertEquals(1, cleanupFailure.getSuppressed().length);
        assertEquals("profile-image object-store cleanup failed",
                cleanupFailure.getSuppressed()[0].getMessage());
        assertFalse(cleanupFailure.toString().contains("avatars/sha256"));
    }

    @Test void failsWhenDeleteReturnsButContentRemainsReadable() {
        FakeObjects objects = new FakeObjects(); objects.retainAfterDelete = true;
        var failure = assertThrows(ProfileImageObjectStoreCapabilityProbeException.class,
                () -> probe(objects).run());
        assertEquals("profile-image object-store cleanup verification failed",
                failure.getMessage());
    }

    private static S3ProfileImageCapabilityProbe probe(FakeObjects objects) {
        return new S3ProfileImageCapabilityProbe(objects, objects, objects,
                S3ProfileImageCapabilityProbeTest::image);
    }
    private static CanonicalProfileImage image() {
        try {
            return new CanonicalProfileImage(PNG, 8, 8,
                    MessageDigest.getInstance("SHA-256").digest(PNG));
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static final class FakeObjects implements ProfileImageObjectWritePort,
            ProfileImageObjectReadPort, ProfileImageObjectDeletionPort {
        private byte[] bytes;
        private ProfileImageObjectEvidence evidence;
        private int writeCalls, readCalls, deleteCalls;
        private boolean repeatClaimsCreated, deleteFails, retainAfterDelete;

        @Override public ProfileImageObjectWriteResult storeIfAbsent(CanonicalProfileImage image) {
            writeCalls++; boolean created = bytes == null || repeatClaimsCreated;
            bytes = image.pngBytes(); byte[] digest = image.contentSha256();
            evidence = new ProfileImageObjectEvidence(
                    ProfileImageObjectEvidence.objectKey(digest), bytes.length, digest, "image/png");
            return new ProfileImageObjectWriteResult(evidence, created);
        }
        @Override public Optional<ProfileImageObjectPayload> read(
                ProfileImageObjectEvidence expected) {
            readCalls++;
            return bytes == null ? Optional.empty()
                    : Optional.of(ProfileImageObjectPayload.copyOf(bytes));
        }
        @Override public void deleteIfPresent(String objectKey) {
            deleteCalls++;
            if (deleteFails) throw new IllegalStateException("signed URL secret");
            if (!retainAfterDelete) { Arrays.fill(bytes, (byte) 0); bytes = null; }
        }
    }
}
