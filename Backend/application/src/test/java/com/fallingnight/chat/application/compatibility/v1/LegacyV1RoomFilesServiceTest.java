package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyV1RoomFilesServiceTest {
    @Test
    void returnsOnlyCompleteAuthorizedProjectionAndRejectsInvalidInput() {
        UUID actor = UUID.randomUUID();
        LegacyV1RoomFiles expected = new LegacyV1RoomFiles(List.of(
                new LegacyV1RoomFile(7, "报告.pdf", 123,
                        Instant.parse("2026-01-02T03:04:05Z"))), 123, 4096);
        LegacyV1RoomFilesService service = new LegacyV1RoomFilesService((actual, room) -> {
            assertEquals(actor, actual);
            assertEquals(9, room);
            return new LegacyV1RoomFilesPort.QueryResult.Authorized(expected);
        });

        assertEquals(new LegacyV1RoomFilesResult.Read(9, expected), service.read(actor, 9));
        assertEquals(LegacyV1RoomFilesResult.Rejected.INVALID_INPUT,
                service.read(actor, 0));
        LegacyV1RoomFilesService denied = new LegacyV1RoomFilesService((a, r) ->
                LegacyV1RoomFilesPort.QueryResult.Rejected.ROOM_ADMIN_REQUIRED);
        assertEquals(LegacyV1RoomFilesResult.Rejected.ROOM_ADMIN_REQUIRED,
                denied.read(actor, 9));
    }

    @Test
    void rejectsIncompleteUnsafeOrInconsistentProjection() {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomFile(0, "a", 1, now));
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomFile(1, "", 1, now));
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomFiles(
                        List.of(new LegacyV1RoomFile(1, "a", 2, now)), 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomFiles(List.of(), 11, 10));
    }
}
