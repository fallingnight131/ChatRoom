package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomRenameServiceTest {
    @Test void normalizesNameAndRejectsPersistenceDrift() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomRenameCommand> captured = new AtomicReference<>();
        var service = new LegacyV1RoomRenameService(command -> {
            captured.set(command);
            return new LegacyV1RoomRenameResult.Renamed(
                    conversation, 7, "Old", command.newName(), true, Instant.EPOCH);
        });
        assertInstanceOf(LegacyV1RoomRenameResult.Renamed.class,
                service.rename(new LegacyV1RoomRenameCommand(actor, 7, "  New Room  ")));
        assertEquals(new LegacyV1RoomRenameCommand(actor, 7, "New Room"), captured.get());

        var drifting = new LegacyV1RoomRenameService(command ->
                new LegacyV1RoomRenameResult.Renamed(
                        conversation, 8, "Old", "Other", true, Instant.EPOCH));
        assertThrows(IllegalStateException.class, () -> drifting.rename(
                new LegacyV1RoomRenameCommand(actor, 7, "New Room")));
    }

    @Test void rejectsInvalidNamesBeforePersistence() {
        var service = new LegacyV1RoomRenameService(command -> {
            throw new AssertionError("invalid input reached persistence");
        });
        UUID actor = UUID.randomUUID();
        assertRejected(service, new LegacyV1RoomRenameCommand(actor, 0, "Room"));
        assertRejected(service, new LegacyV1RoomRenameCommand(actor, 7, "   "));
        assertRejected(service, new LegacyV1RoomRenameCommand(actor, 7, "bad\nroom"));
        assertRejected(service, new LegacyV1RoomRenameCommand(
                actor, 7, "界".repeat(101)));
    }

    private static void assertRejected(LegacyV1RoomRenameService service,
            LegacyV1RoomRenameCommand command) {
        assertEquals(LegacyV1RoomRenameResult.Rejected.INVALID_INPUT,
                service.rename(command));
    }
}
