package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.application.profile.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Explicit destructive probe for the exact inactive profile-image object contract. */
public final class S3ProfileImageCapabilityProbe {
    private final ProfileImageObjectWritePort writes;
    private final ProfileImageObjectReadPort reads;
    private final ProfileImageObjectDeletionPort deletes;
    private final Supplier<CanonicalProfileImage> images;

    public S3ProfileImageCapabilityProbe(ProfileImageObjectWritePort writes,
            ProfileImageObjectReadPort reads, ProfileImageObjectDeletionPort deletes,
            Supplier<CanonicalProfileImage> images) {
        this.writes = Objects.requireNonNull(writes, "writes");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.deletes = Objects.requireNonNull(deletes, "deletes");
        this.images = Objects.requireNonNull(images, "images");
    }

    public CapabilityReport run() {
        CanonicalProfileImage image = Objects.requireNonNull(
                images.get(), "profile-image probe supplier returned null");
        ProfileImageObjectEvidence expected = evidence(image);
        ProfileImageObjectStoreCapabilityProbeException failure = null;
        CapabilityReport report = null;
        try { report = execute(image, expected); }
        catch (RuntimeException exception) {
            failure = normalize("profile-image object-store capability probe failed", exception);
        }
        try {
            deletes.deleteIfPresent(expected.objectKey());
            Optional<ProfileImageObjectPayload> remaining = reads.read(expected);
            if (remaining.isPresent()) {
                remaining.orElseThrow().close();
                throw new ProfileImageObjectStoreCapabilityProbeException(
                        "profile-image object-store cleanup verification failed");
            }
        } catch (RuntimeException exception) {
            var cleanup = normalize("profile-image object-store cleanup failed", exception);
            if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
        return Objects.requireNonNull(report, "capability report");
    }

    private CapabilityReport execute(CanonicalProfileImage image,
            ProfileImageObjectEvidence expected) {
        ProfileImageObjectWriteResult first = writes.storeIfAbsent(image);
        if (!first.created() || !matches(first.evidence(), expected))
            throw new ProfileImageObjectStoreCapabilityProbeException(
                    "profile-image provider did not create fresh exact content");
        ProfileImageObjectWriteResult retry = writes.storeIfAbsent(image);
        if (retry.created() || !matches(retry.evidence(), expected))
            throw new ProfileImageObjectStoreCapabilityProbeException(
                    "profile-image provider did not converge create-only retry");
        Optional<ProfileImageObjectPayload> found = reads.read(expected);
        if (found.isEmpty())
            throw new ProfileImageObjectStoreCapabilityProbeException(
                    "profile-image provider omitted created content");
        try (ProfileImageObjectPayload payload = found.orElseThrow()) {
            boolean exact = payload.withCopy(bytes -> Arrays.equals(bytes, image.pngBytes()));
            if (!exact)
                throw new ProfileImageObjectStoreCapabilityProbeException(
                        "profile-image provider returned different bytes");
        }
        return new CapabilityReport(true, true, true, true);
    }

    private static boolean matches(ProfileImageObjectEvidence actual,
            ProfileImageObjectEvidence expected) {
        return actual.objectKey().equals(expected.objectKey())
                && actual.byteSize() == expected.byteSize()
                && actual.mediaType().equals(expected.mediaType())
                && MessageDigest.isEqual(actual.contentSha256(), expected.contentSha256());
    }

    private static ProfileImageObjectEvidence evidence(CanonicalProfileImage image) {
        byte[] digest = image.contentSha256();
        return new ProfileImageObjectEvidence(ProfileImageObjectEvidence.objectKey(digest),
                image.pngBytes().length, digest, "image/png");
    }

    private static ProfileImageObjectStoreCapabilityProbeException normalize(
            String message, RuntimeException exception) {
        if (exception instanceof ProfileImageObjectStoreCapabilityProbeException safe)
            return safe;
        return new ProfileImageObjectStoreCapabilityProbeException(message);
    }

    public record CapabilityReport(boolean firstPutVerified, boolean retryVerified,
            boolean readVerified, boolean cleanupVerified) { }
}
