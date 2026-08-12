package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomReadServiceTest {
    @Test void validatesRoomAndPreservesAuthenticatedActor() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1RoomReadService(command -> {
            calls.incrementAndGet(); assertEquals(actor, command.actorAccountId());
            assertEquals(7, command.legacyRoomId());
            return new LegacyV1RoomReadResult.Marked(conversation, 7, 3, 8, true);
        });
        assertEquals(8, ((LegacyV1RoomReadResult.Marked) service.markRead(
                new LegacyV1RoomReadCommand(actor, 7))).lastReadSequence());
        assertEquals(LegacyV1RoomReadResult.Rejected.INVALID_ROOM_ID,
                service.markRead(new LegacyV1RoomReadCommand(actor, 0)));
        assertEquals(1, calls.get());
    }

    @Test void rejectsInconsistentResultAndCursorFlags() {
        UUID actor = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> new LegacyV1RoomReadService(command ->
                new LegacyV1RoomReadResult.Marked(UUID.randomUUID(), 8, 1, 2, true))
                .markRead(new LegacyV1RoomReadCommand(actor, 7)));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomReadResult.Marked(UUID.randomUUID(), 7, 2, 2, true));
        assertDoesNotThrow(() -> new LegacyV1RoomReadResult.Marked(
                UUID.randomUUID(), 7, 2, 2, false));
    }
}
