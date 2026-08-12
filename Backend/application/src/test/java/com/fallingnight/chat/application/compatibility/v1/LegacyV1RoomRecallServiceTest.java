package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomRecallServiceTest {
    @Test void validatesIdsAndPreservesServerBoundActorAndResource() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        var service = new LegacyV1RoomRecallService(command -> {
            calls.incrementAndGet(); assertEquals(actor, command.actorAccountId());
            assertEquals(7, command.legacyRoomId()); assertEquals(101, command.legacyMessageId());
            return new LegacyV1RoomRecallResult.Recalled(false, conversation, 7, 101, 8,
                    Instant.parse("2026-08-13T12:00:00Z"));
        });
        assertFalse(((LegacyV1RoomRecallResult.Recalled) service.recall(
                new LegacyV1RoomRecallCommand(actor, 7, 101))).duplicate());
        assertEquals(LegacyV1RoomRecallResult.Rejected.INVALID_REQUEST,
                service.recall(new LegacyV1RoomRecallCommand(actor, 0, 101)));
        assertEquals(LegacyV1RoomRecallResult.Rejected.INVALID_REQUEST,
                service.recall(new LegacyV1RoomRecallCommand(actor, 7, -1)));
        assertEquals(1, calls.get());
    }

    @Test void rejectsPortIdentitySubstitution() {
        UUID actor = UUID.randomUUID();
        var service = new LegacyV1RoomRecallService(command ->
                new LegacyV1RoomRecallResult.Recalled(false, UUID.randomUUID(), 8, 101, 9,
                        Instant.EPOCH));
        assertThrows(IllegalStateException.class, () -> service.recall(
                new LegacyV1RoomRecallCommand(actor, 7, 101)));
    }
}
