package com.fallingnight.chat.application.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ProfileImageMetadataContractTest {
    @Test void supportsStableAccountAndLegacyRoomTargets() {
        UUID actor = UUID.randomUUID();
        assertEquals(actor, new ProfileImageTarget.Account(actor).actorAccountId());
        assertEquals(7, new ProfileImageTarget.LegacyRoom(actor, 7).legacyRoomId());
        assertThrows(IllegalArgumentException.class,
                () -> new ProfileImageTarget.LegacyRoom(actor, 0));
    }

    @Test void validatesDimensionsAndFirstOnlyEffects() {
        byte[] digest = new byte[32];
        ProfileImageObjectEvidence evidence = new ProfileImageObjectEvidence(
                ProfileImageObjectEvidence.objectKey(digest), 9, digest, "image/png");
        UUID actor = UUID.randomUUID();
        assertDoesNotThrow(() -> new ProfileImageMetadataCommand(
                new ProfileImageTarget.Account(actor), evidence, 256, 256));
        assertThrows(IllegalArgumentException.class, () -> new ProfileImageMetadataCommand(
                new ProfileImageTarget.Account(actor), evidence, 0, 256));
        assertThrows(IllegalArgumentException.class, () ->
                new ProfileImageMetadataResult.Committed(evidence.objectKey(), 1, false,
                        Instant.EPOCH, Optional.of("avatars/sha256/old.png"), Set.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new ProfileImageMetadataResult.Committed(evidence.objectKey(), 1, true,
                        Instant.EPOCH, Optional.of(evidence.objectKey()), Set.of()));
    }
}
