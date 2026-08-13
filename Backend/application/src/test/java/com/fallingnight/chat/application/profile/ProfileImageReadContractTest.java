package com.fallingnight.chat.application.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ProfileImageReadContractTest {
    @Test void acceptsBoundedLegacyUsernameAndPositiveRoom() {
        UUID actor = UUID.randomUUID();
        assertEquals("imported-peer",
                new ProfileImageReadTarget.AccountByUsername(actor, "imported-peer").username());
        assertEquals(7, new ProfileImageReadTarget.LegacyRoom(actor, 7).legacyRoomId());
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileImageReadTarget.AccountByUsername(actor, " bad "));
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileImageReadTarget.LegacyRoom(actor, 0));
    }

    @Test void foundProjectionRequiresVersionedBoundedEvidence() {
        byte[] digest = new byte[32];
        var evidence = new ProfileImageObjectEvidence(
                ProfileImageObjectEvidence.objectKey(digest), 9, digest, "image/png");
        assertDoesNotThrow(() -> new ProfileImageReadResult.Found(
                evidence, 256, 256, 1, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImageReadResult.Found(
                evidence, 256, 256, 0, Instant.EPOCH));
    }
}
