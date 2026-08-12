package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomLeaveServiceTest {
    @Test void delegatesServerBoundIdentityAndReturnsAtomicOutcome() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomLeaveIntent> captured = new AtomicReference<>();
        var expected = new LegacyV1RoomLeaveResult.Left(conversation, 77, actor,
                true, false, Optional.of(new LegacyV1RoomLeaveResult.OwnershipTransfer(
                        UUID.randomUUID(), "Next owner")));
        var service = new LegacyV1RoomLeaveService(intent -> {
            captured.set(intent); return expected;
        });

        assertEquals(expected, service.leave(actor, 77));
        assertEquals(new LegacyV1RoomLeaveIntent(actor, 77), captured.get());
    }

    @Test void rejectsInvalidRoomIdBeforePersistence() {
        UUID actor = UUID.randomUUID();
        var service = new LegacyV1RoomLeaveService(intent -> fail());

        assertEquals(LegacyV1RoomLeaveResult.Rejected.INVALID_INPUT,
                service.leave(actor, 0));
        assertEquals(LegacyV1RoomLeaveResult.Rejected.INVALID_INPUT,
                service.leave(actor, (long) Integer.MAX_VALUE + 1));
    }

    @Test void preservesStableBusinessRejections() {
        UUID actor = UUID.randomUUID();
        for (LegacyV1RoomLeaveResult.Rejected rejected
                : LegacyV1RoomLeaveResult.Rejected.values()) {
            if (rejected == LegacyV1RoomLeaveResult.Rejected.INVALID_INPUT) continue;
            var service = new LegacyV1RoomLeaveService(intent -> rejected);
            assertEquals(rejected, service.leave(actor, 77));
        }
    }

    @Test void failsClosedOnNullOrSubstitutedPersistenceResults() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> new LegacyV1RoomLeaveService(intent -> null).leave(actor, 77));
        var wrongActor = new LegacyV1RoomLeaveService(intent ->
                new LegacyV1RoomLeaveResult.Left(conversation, 77, UUID.randomUUID(),
                        true, false, Optional.empty()));
        assertThrows(IllegalStateException.class, () -> wrongActor.leave(actor, 77));
        var wrongRoom = new LegacyV1RoomLeaveService(intent ->
                new LegacyV1RoomLeaveResult.Left(conversation, 78, actor,
                        true, false, Optional.empty()));
        assertThrows(IllegalStateException.class, () -> wrongRoom.leave(actor, 77));
    }

    @Test void resultRejectsImpossibleOwnershipTransitions() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        var transfer = new LegacyV1RoomLeaveResult.OwnershipTransfer(
                UUID.randomUUID(), "Successor");
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomLeaveResult.Left(conversation, 77, actor,
                        false, false, Optional.of(transfer)));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomLeaveResult.Left(conversation, 77, actor,
                        true, true, Optional.of(transfer)));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomLeaveResult.Left(conversation, 77, actor,
                        true, false, Optional.of(
                                new LegacyV1RoomLeaveResult.OwnershipTransfer(actor, "Actor"))));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1RoomLeaveResult.OwnershipTransfer(
                        UUID.randomUUID(), "x".repeat(101)));
    }
}
