package com.fallingnight.chat.application.profile;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;

/** Authorize, canonicalize, create the immutable object, then commit its pointer. */
public final class ProfileImageMutationService implements ProfileImageMutationUseCase {
    private final ProfileImageMutationAuthorizationPort authorization;
    private final ProfileImageInspectionPort inspector;
    private final ProfileImageObjectWritePort objects;
    private final ProfileImageMetadataPort metadata;
    private final ProfileImageOrphanCleanupPort cleanup;

    public ProfileImageMutationService(ProfileImageMutationAuthorizationPort authorization,
            ProfileImageInspectionPort inspector, ProfileImageObjectWritePort objects,
            ProfileImageMetadataPort metadata, ProfileImageOrphanCleanupPort cleanup) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    /** Takes ownership of {@code upload} and clears it before returning. */
    @Override public ProfileImageMutationResult change(ProfileImageTarget target,
            LegacyV1AvatarUpload upload) {
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(upload, "upload");
        try (upload) {
            ProfileImageMutationAuthorization allowed = Objects.requireNonNull(
                    authorization.authorize(target), "profile image authorization");
            if (allowed != ProfileImageMutationAuthorization.AUTHORIZED)
                return authorizationRejected(allowed);
            Optional<CanonicalProfileImage> inspected = inspector.inspect(upload);
            if (inspected.isEmpty()) return ProfileImageMutationResult.Rejected.INVALID_IMAGE;
            CanonicalProfileImage image = inspected.orElseThrow();
            ProfileImageObjectWriteResult stored = Objects.requireNonNull(
                    objects.storeIfAbsent(image), "profile image object result");
            requireExact(image, stored.evidence());
            boolean cleanupAttempted = false;
            try {
                ProfileImageMetadataResult committed = Objects.requireNonNull(metadata.commit(
                        new ProfileImageMetadataCommand(target, stored.evidence(),
                                image.width(), image.height())), "profile image metadata result");
                if (committed instanceof ProfileImageMetadataResult.Committed success) {
                    Optional<ProfileImageObjectPayload> notification = success.changed()
                            ? Optional.of(ProfileImageObjectPayload.copyOf(image.pngBytes()))
                            : Optional.empty();
                    return new ProfileImageMutationResult.Committed(success, notification);
                }
                if (stored.created()) {
                    cleanupAttempted = true;
                    cleanup.requestIfUnreferenced(stored.evidence());
                }
                return metadataRejected((ProfileImageMetadataResult.Rejected) committed);
            } catch (RuntimeException exception) {
                if (stored.created() && !cleanupAttempted) {
                    try { cleanup.requestIfUnreferenced(stored.evidence()); }
                    catch (RuntimeException cleanupFailure) {
                        exception.addSuppressed(cleanupFailure);
                    }
                }
                throw exception;
            }
        }
    }

    private static void requireExact(CanonicalProfileImage image,
            ProfileImageObjectEvidence evidence) {
        if (evidence.byteSize() != image.pngBytes().length
                || !"image/png".equals(evidence.mediaType())
                || !MessageDigest.isEqual(evidence.contentSha256(), image.contentSha256())
                || !evidence.objectKey().equals(
                        ProfileImageObjectEvidence.objectKey(image.contentSha256())))
            throw new ProfileImageIntegrityException(
                    "object writer returned evidence for different profile image bytes");
    }

    private static ProfileImageMutationResult.Rejected authorizationRejected(
            ProfileImageMutationAuthorization result) {
        return switch (result) {
            case ACCOUNT_UNAVAILABLE -> ProfileImageMutationResult.Rejected.ACCOUNT_UNAVAILABLE;
            case ROOM_ADMIN_REQUIRED -> ProfileImageMutationResult.Rejected.ROOM_ADMIN_REQUIRED;
            case AUTHORIZED -> throw new IllegalArgumentException("authorization is not rejected");
        };
    }

    private static ProfileImageMutationResult.Rejected metadataRejected(
            ProfileImageMetadataResult.Rejected result) {
        return switch (result) {
            case ACCOUNT_UNAVAILABLE -> ProfileImageMutationResult.Rejected.ACCOUNT_UNAVAILABLE;
            case ROOM_ADMIN_REQUIRED -> ProfileImageMutationResult.Rejected.ROOM_ADMIN_REQUIRED;
            case OBJECT_EVIDENCE_CONFLICT ->
                    ProfileImageMutationResult.Rejected.OBJECT_EVIDENCE_CONFLICT;
        };
    }
}
