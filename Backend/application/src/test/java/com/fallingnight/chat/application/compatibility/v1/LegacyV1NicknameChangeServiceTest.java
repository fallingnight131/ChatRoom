package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1NicknameChangeServiceTest {
    @Test void normalizesUnicodeAndRejectsPersistenceDrift() {
        UUID actor = UUID.randomUUID();
        AtomicReference<LegacyV1NicknameChangeCommand> captured = new AtomicReference<>();
        var service = new LegacyV1NicknameChangeService(command -> {
            captured.set(command);
            return new LegacyV1NicknameChangeResult.Changed(actor, "Old",
                    command.newDisplayName(), true, Instant.EPOCH, List.of());
        });
        assertInstanceOf(LegacyV1NicknameChangeResult.Changed.class,
                service.change(new LegacyV1NicknameChangeCommand(actor, "  e\u0301  ")));
        assertEquals(new LegacyV1NicknameChangeCommand(actor, "é"), captured.get());

        var drifting = new LegacyV1NicknameChangeService(command ->
                new LegacyV1NicknameChangeResult.Changed(UUID.randomUUID(), "Old",
                        command.newDisplayName(), true, Instant.EPOCH, List.of()));
        assertThrows(IllegalStateException.class, () -> drifting.change(
                new LegacyV1NicknameChangeCommand(actor, "New")));
    }

    @Test void rejectsInvalidNamesBeforePersistence() {
        var service = new LegacyV1NicknameChangeService(command -> {
            throw new AssertionError("invalid input reached persistence");
        });
        UUID actor = UUID.randomUUID();
        assertRejected(service, actor, null);
        assertRejected(service, actor, "   ");
        assertRejected(service, actor, "bad\nname");
        assertRejected(service, actor, "界".repeat(21));
    }

    @Test void unchangedResultsCannotCarryNotificationEffects() {
        UUID actor = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1NicknameChangeResult.Changed(actor, "Same", "Same", false,
                        Instant.EPOCH, List.of(new LegacyV1NicknameChangeResult.RoomAudience(
                                7, java.util.Set.of(actor)))));
    }

    private static void assertRejected(LegacyV1NicknameChangeService service,
            UUID actor, String displayName) {
        assertEquals(LegacyV1NicknameChangeResult.Rejected.INVALID_INPUT,
                service.change(new LegacyV1NicknameChangeCommand(actor, displayName)));
    }
}
