package com.fallingnight.chat.application.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class ProfileImageLoadServiceTest {
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    private static final ProfileImageReadTarget TARGET =
            new ProfileImageReadTarget.AccountByUsername(UUID.randomUUID(), "peer");

    @Test void authorizesThenLoadsAndVerifiesPrivateBytes() throws Exception {
        var evidence = evidence(PNG);
        var found = new ProfileImageReadResult.Found(
                evidence, 16, 24, 3, Instant.parse("2026-08-13T00:00:00Z"));
        var service = new ProfileImageLoadService(target -> found,
                object -> Optional.of(ProfileImageObjectPayload.copyOf(PNG)));

        var loaded = assertInstanceOf(ProfileImageLoadResult.Loaded.class,
                service.load(TARGET));
        assertEquals(11, loaded.payload().byteSize());
        assertEquals(3, loaded.version());
        loaded.close();
        assertThrows(IllegalStateException.class, loaded.payload()::byteSize);
    }

    @Test void doesNotTouchObjectStorageForMissingOrRejectedMetadata() {
        var touched = new AtomicBoolean();
        ProfileImageObjectReadPort objects = ignored -> {
            touched.set(true); return Optional.empty();
        };
        assertEquals(ProfileImageLoadResult.Missing.INSTANCE,
                new ProfileImageLoadService(target -> ProfileImageReadResult.Missing.INSTANCE,
                        objects).load(TARGET));
        assertEquals(ProfileImageLoadResult.Rejected.ACCESS_DENIED,
                new ProfileImageLoadService(
                        target -> ProfileImageReadResult.Rejected.ACCESS_DENIED, objects)
                        .load(TARGET));
        assertFalse(touched.get());
    }

    @Test void treatsMissingOrMismatchedPrivateObjectAsIntegrityFailure() throws Exception {
        var evidence = evidence(PNG);
        var found = new ProfileImageReadResult.Found(evidence, 16, 24, 1, Instant.EPOCH);
        assertThrows(ProfileImageIntegrityException.class,
                () -> new ProfileImageLoadService(target -> found, object -> Optional.empty())
                        .load(TARGET));

        byte[] changed = PNG.clone(); changed[10] = 4;
        var supplied = ProfileImageObjectPayload.copyOf(changed);
        assertThrows(ProfileImageIntegrityException.class,
                () -> new ProfileImageLoadService(target -> found,
                        object -> Optional.of(supplied)).load(TARGET));
        assertThrows(IllegalStateException.class, supplied::byteSize);

        var shortPayload = ProfileImageObjectPayload.copyOf(new byte[] {1});
        assertThrows(ProfileImageIntegrityException.class,
                () -> new ProfileImageLoadService(target -> found,
                        object -> Optional.of(shortPayload)).load(TARGET));
        assertThrows(IllegalStateException.class, shortPayload::byteSize);
    }

    @Test void payloadOwnsAndClearsItsBytes() {
        byte[] source = PNG.clone();
        var payload = ProfileImageObjectPayload.copyOf(source);
        source[0] = 0;
        assertEquals((byte) 0x89, payload.withCopy(bytes -> bytes[0]).byteValue());
        payload.withCopy(bytes -> { bytes[0] = 0; return null; });
        assertEquals((byte) 0x89, payload.withCopy(bytes -> bytes[0]).byteValue());
        payload.close();
        assertThrows(IllegalStateException.class, payload::byteSize);
    }

    private static ProfileImageObjectEvidence evidence(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return new ProfileImageObjectEvidence(ProfileImageObjectEvidence.objectKey(digest),
                bytes.length, digest, "image/png");
    }
}
