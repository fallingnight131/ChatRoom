package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomMessageDeletionServiceTest {
    @Test void normalizesSelectionAndWholeSecondCutoffs() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomMessageDeletionIntent> captured = new AtomicReference<>();
        var service = new LegacyV1RoomMessageDeletionService(intent -> {
            captured.set(intent);
            return new LegacyV1RoomMessageDeletionResult.Deleted(false, conversation, 7,
                    intent.clientOperationId(), intent.mode(), intent.legacyMessageIds(), List.of(),
                    intent.cutoffEpochMillis(), intent.legacyMessageIds().size(), 9,
                    Instant.EPOCH);
        });
        assertInstanceOf(LegacyV1RoomMessageDeletionResult.Deleted.class,
                service.delete(new LegacyV1RoomMessageDeletionCommand(actor, 7, "operation",
                        "selected", List.of(9L, 3L), 0)));
        assertEquals(List.of(3L, 9L), captured.get().legacyMessageIds());
        String selectedFingerprint = captured.get().commandFingerprint();

        service.delete(new LegacyV1RoomMessageDeletionCommand(actor, 7, "operation-2",
                "before", List.of(), 12_999));
        assertEquals(12_000, captured.get().cutoffEpochMillis());
        assertNotEquals(selectedFingerprint, captured.get().commandFingerprint());
    }

    @Test void rejectsMalformedModeShapeAndOperationBeforePersistence() {
        var service = new LegacyV1RoomMessageDeletionService(intent -> {
            throw new AssertionError("invalid input reached persistence");
        });
        UUID actor = UUID.randomUUID();
        assertRejected(service, new LegacyV1RoomMessageDeletionCommand(
                actor, 7, "operation", "selected", List.of(), 0));
        assertRejected(service, new LegacyV1RoomMessageDeletionCommand(
                actor, 7, "operation", "all", List.of(1L), 0));
        assertRejected(service, new LegacyV1RoomMessageDeletionCommand(
                actor, 7, "operation", "before", List.of(), 0));
        assertRejected(service, new LegacyV1RoomMessageDeletionCommand(
                actor, 7, "operation", "all", List.of(), 1));
        assertRejected(service, new LegacyV1RoomMessageDeletionCommand(
                actor, 7, "operation", "selected", List.of(1L, 1L), 0));
        assertRejected(service, new LegacyV1RoomMessageDeletionCommand(
                actor, 7, "bad\noperation", "all", List.of(), 0));
        assertRejected(service, new LegacyV1RoomMessageDeletionCommand(
                actor, 7, "operation", "unknown", List.of(), 0));
    }

    @Test void rejectsPersistenceIdentityDrift() {
        UUID actor = UUID.randomUUID();
        var service = new LegacyV1RoomMessageDeletionService(intent ->
                new LegacyV1RoomMessageDeletionResult.Deleted(false, UUID.randomUUID(), 8,
                        "other", LegacyV1RoomMessageDeletionMode.ALL, List.of(), List.of(),
                        0, 0, 1, Instant.EPOCH));
        assertThrows(IllegalStateException.class, () -> service.delete(
                new LegacyV1RoomMessageDeletionCommand(
                        actor, 7, "operation", "all", List.of(), 0)));
    }

    private static void assertRejected(LegacyV1RoomMessageDeletionService service,
            LegacyV1RoomMessageDeletionCommand command) {
        assertEquals(LegacyV1RoomMessageDeletionResult.Rejected.INVALID_INPUT,
                service.delete(command));
    }
}
