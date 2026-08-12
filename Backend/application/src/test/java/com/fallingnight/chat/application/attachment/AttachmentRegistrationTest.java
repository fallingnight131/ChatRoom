package com.fallingnight.chat.application.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttachmentRegistrationTest {
    @Test
    void canonicalizesMimeTypeAndOwnsTheExpectedHash() {
        byte[] hash = new byte[32];
        hash[0] = 7;
        AttachmentRegistration registration = registration(
                "报告.pdf", " Application/PDF ", 1024, hash);
        hash[0] = 9;

        assertEquals("application/pdf", registration.mediaType());
        assertArrayEquals(new byte[] {7},
                new byte[] {registration.contentSha256()[0]});
        byte[] returned = registration.contentSha256();
        returned[0] = 3;
        assertEquals(7, registration.contentSha256()[0]);
    }

    @Test
    void rejectsPathsControlsInvalidMimeSizeAndHash() {
        assertThrows(IllegalArgumentException.class,
                () -> registration("../secret.txt", "text/plain", 1, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> registration("folder/file.txt", "text/plain", 1, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> registration("bad\u0000name", "text/plain", 1, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> registration("file.txt", "text/plain; charset=utf-8", 1, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> registration("file.txt", "文本/plain", 1, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> registration("file.txt", "text/plain", 0, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> registration("file.txt", "text/plain",
                        AttachmentRegistration.MAX_BYTE_SIZE + 1, new byte[32]));
        assertThrows(IllegalArgumentException.class,
                () -> registration("file.txt", "text/plain", 1, new byte[31]));
    }

    private static AttachmentRegistration registration(
            String name, String mediaType, long size, byte[] hash) {
        return new AttachmentRegistration(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "client-attachment-1", name, mediaType, size, hash);
    }
}
