package com.fallingnight.chat.application.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisteredAttachmentTest {
    private static final Instant CREATED = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void acceptsUnavailableHistoryOnlyWithoutFabricatedObjectEvidence() {
        RegisteredAttachment unavailable = unavailable(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("legacy file expired"));

        assertEquals(AttachmentState.UNAVAILABLE, unavailable.state());
        assertEquals(Optional.empty(), unavailable.objectKey());
        assertEquals(Optional.empty(), unavailable.contentSha256());
        assertEquals("legacy file expired", unavailable.unavailableReason().orElseThrow());
    }

    @Test
    void rejectsUnavailableHistoryWithObjectEvidenceOrMissingReason() {
        assertThrows(IllegalArgumentException.class, () -> unavailable(
                Optional.of("attachments/fabricated"), Optional.of("application/octet-stream"),
                Optional.of(new byte[32]), Optional.of("legacy file expired")));
        assertThrows(IllegalArgumentException.class, () -> unavailable(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    private static RegisteredAttachment unavailable(
            Optional<String> objectKey,
            Optional<String> mediaType,
            Optional<byte[]> hash,
            Optional<String> reason) {
        return new RegisteredAttachment(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                "v1-import-room-file-7", objectKey, "expired.pdf", mediaType, 123, hash,
                AttachmentState.UNAVAILABLE, CREATED, Optional.empty(), Optional.empty(),
                Optional.of(CREATED.plusSeconds(1)), reason);
    }
}
