package com.fallingnight.chat.application.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProfileImageMutationServiceTest {
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
    private static final ProfileImageTarget TARGET =
            new ProfileImageTarget.Account(UUID.randomUUID());

    @Test void authorizesBeforeWorkThenStoresBeforeMetadataCommitAndClearsUpload()
            throws Exception {
        CanonicalProfileImage image = image(PNG); StringBuilder order = new StringBuilder();
        AtomicReference<ProfileImageMetadataCommand> command = new AtomicReference<>();
        LegacyV1AvatarUpload upload = LegacyV1AvatarUpload.copyOf(PNG);
        var service = new ProfileImageMutationService(target -> {
            order.append("authorize>"); return ProfileImageMutationAuthorization.AUTHORIZED;
        }, value -> { order.append("inspect>"); return Optional.of(image); }, value -> {
            order.append("store>"); return new ProfileImageObjectWriteResult(evidence(image), true);
        }, value -> {
            order.append("commit"); command.set(value);
            return committed(value.object().objectKey());
        }, value -> fail("committed object must not be orphaned"));

        var result = assertInstanceOf(ProfileImageMutationResult.Committed.class,
                service.change(TARGET, upload));

        assertEquals("authorize>inspect>store>commit", order.toString());
        assertEquals(image.width(), command.get().width());
        assertEquals(image.height(), command.get().height());
        assertEquals(result.metadata().objectKey(), command.get().object().objectKey());
        assertEquals(PNG.length, result.notificationPayload().orElseThrow().byteSize());
        result.close();
        assertThrows(IllegalStateException.class,
                result.notificationPayload().orElseThrow()::byteSize);
        assertThrows(IllegalStateException.class, upload::byteSize);
    }

    @Test void rejectsUnauthorizedOrInvalidInputBeforeCreatingObjects() throws Exception {
        AtomicBoolean touched = new AtomicBoolean();
        CanonicalProfileImage canonical = image(PNG);
        LegacyV1AvatarUpload unauthorized = LegacyV1AvatarUpload.copyOf(PNG);
        var denied = new ProfileImageMutationService(
                target -> ProfileImageMutationAuthorization.ACCOUNT_UNAVAILABLE,
                value -> { touched.set(true); return Optional.of(canonical); },
                value -> { touched.set(true); throw new AssertionError(); },
                value -> { touched.set(true); throw new AssertionError(); },
                value -> { touched.set(true); });
        assertEquals(ProfileImageMutationResult.Rejected.ACCOUNT_UNAVAILABLE,
                denied.change(TARGET, unauthorized));
        assertFalse(touched.get());
        assertThrows(IllegalStateException.class, unauthorized::byteSize);

        AtomicBoolean stored = new AtomicBoolean();
        var invalid = new ProfileImageMutationService(
                target -> ProfileImageMutationAuthorization.AUTHORIZED,
                value -> Optional.empty(),
                value -> { stored.set(true); throw new AssertionError(); },
                value -> { throw new AssertionError(); }, value -> { });
        assertEquals(ProfileImageMutationResult.Rejected.INVALID_IMAGE,
                invalid.change(TARGET, LegacyV1AvatarUpload.copyOf(PNG)));
        assertFalse(stored.get());
    }

    @Test void durablyRequestsCleanupForNewObjectWhenCommitRejectsOrFails()
            throws Exception {
        CanonicalProfileImage image = image(PNG);
        AtomicReference<ProfileImageObjectEvidence> orphan = new AtomicReference<>();
        var rejected = service(image, true,
                command -> ProfileImageMetadataResult.Rejected.ROOM_ADMIN_REQUIRED,
                orphan::set);
        assertEquals(ProfileImageMutationResult.Rejected.ROOM_ADMIN_REQUIRED,
                rejected.change(TARGET, LegacyV1AvatarUpload.copyOf(PNG)));
        assertEquals(evidence(image).objectKey(), orphan.get().objectKey());

        orphan.set(null); RuntimeException failure = new IllegalStateException("database down");
        var failed = service(image, true, command -> { throw failure; }, orphan::set);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> failed.change(TARGET, LegacyV1AvatarUpload.copyOf(PNG))));
        assertNotNull(orphan.get());

        AtomicBoolean cleanup = new AtomicBoolean();
        var existing = service(image, false,
                command -> ProfileImageMetadataResult.Rejected.ACCOUNT_UNAVAILABLE,
                value -> cleanup.set(true));
        assertEquals(ProfileImageMutationResult.Rejected.ACCOUNT_UNAVAILABLE,
                existing.change(TARGET, LegacyV1AvatarUpload.copyOf(PNG)));
        assertFalse(cleanup.get());
    }

    @Test void rejectsObjectWriterEvidenceThatDoesNotMatchCanonicalBytes()
            throws Exception {
        CanonicalProfileImage image = image(PNG);
        byte[] other = PNG.clone(); other[8] = 2;
        CanonicalProfileImage otherImage = image(other);
        var service = new ProfileImageMutationService(
                target -> ProfileImageMutationAuthorization.AUTHORIZED,
                value -> Optional.of(image),
                value -> new ProfileImageObjectWriteResult(evidence(otherImage), true),
                value -> { throw new AssertionError(); }, value -> { });
        assertThrows(ProfileImageIntegrityException.class,
                () -> service.change(TARGET, LegacyV1AvatarUpload.copyOf(PNG)));
    }

    private static ProfileImageMutationService service(CanonicalProfileImage image,
            boolean created, ProfileImageMetadataPort metadata,
            ProfileImageOrphanCleanupPort cleanup) {
        return new ProfileImageMutationService(
                target -> ProfileImageMutationAuthorization.AUTHORIZED,
                value -> Optional.of(image),
                value -> new ProfileImageObjectWriteResult(evidence(image), created),
                metadata, cleanup);
    }

    private static ProfileImageMetadataResult.Committed committed(String key) {
        return new ProfileImageMetadataResult.Committed(
                key, 1, true, Instant.EPOCH, Optional.empty(), Set.of());
    }

    private static CanonicalProfileImage image(byte[] bytes) throws Exception {
        return new CanonicalProfileImage(bytes, 16, 24,
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static ProfileImageObjectEvidence evidence(CanonicalProfileImage image) {
        byte[] digest = image.contentSha256();
        return new ProfileImageObjectEvidence(ProfileImageObjectEvidence.objectKey(digest),
                image.pngBytes().length, digest, "image/png");
    }
}
