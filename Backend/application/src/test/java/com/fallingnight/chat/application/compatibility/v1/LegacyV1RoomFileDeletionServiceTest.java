package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomFileDeletionServiceTest {
    @Test
    void validatesNormalizesAndBindsActorAndOperation() {
        UUID actor = UUID.randomUUID();
        AtomicReference<LegacyV1RoomFileDeletionIntent> captured = new AtomicReference<>();
        var service = new LegacyV1RoomFileDeletionService(intent -> {
            captured.set(intent);
            return new LegacyV1RoomFileDeletionResult.Deleted(false, UUID.randomUUID(), 7,
                    "operation-1", List.of(701L, 702L), List.of(9L, 10L), 12,
                    Instant.EPOCH, 100, 1000);
        });

        var result = service.delete(new LegacyV1RoomFileDeletionCommand(
                actor, 7, "operation-1", List.of(10L, 9L)));
        assertEquals(2, ((LegacyV1RoomFileDeletionResult.Deleted) result).deletedCount());
        assertEquals(actor, captured.get().actorAccountId());
        assertEquals(List.of(9L, 10L), captured.get().legacyFileIds());
        assertEquals(64, captured.get().commandFingerprint().length());

        AtomicReference<String> second = new AtomicReference<>();
        new LegacyV1RoomFileDeletionService(intent -> {
            second.set(intent.commandFingerprint());
            return LegacyV1RoomFileDeletionResult.Rejected.ROOM_ADMIN_REQUIRED;
        }).delete(new LegacyV1RoomFileDeletionCommand(
                actor, 8, "operation-1", List.of(9L, 10L)));
        assertNotEquals(captured.get().commandFingerprint(), second.get());
    }

    @Test
    void rejectsEmptyDuplicateInvalidAndOversizedInputsBeforePersistence() {
        var service = new LegacyV1RoomFileDeletionService(intent -> {
            throw new AssertionError("invalid input reached persistence");
        });
        UUID actor = UUID.randomUUID();
        assertEquals(LegacyV1RoomFileDeletionResult.Rejected.INVALID_INPUT,
                service.delete(new LegacyV1RoomFileDeletionCommand(
                        actor, 7, "operation", List.of())));
        assertEquals(LegacyV1RoomFileDeletionResult.Rejected.INVALID_INPUT,
                service.delete(new LegacyV1RoomFileDeletionCommand(
                        actor, 7, "operation", List.of(1L, 1L))));
        assertEquals(LegacyV1RoomFileDeletionResult.Rejected.INVALID_INPUT,
                service.delete(new LegacyV1RoomFileDeletionCommand(
                        actor, 7, "bad\noperation", List.of(1L))));
        assertEquals(LegacyV1RoomFileDeletionResult.Rejected.INVALID_INPUT,
                service.delete(new LegacyV1RoomFileDeletionCommand(
                        actor, 7, "operation",
                        java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList())));
    }
}
