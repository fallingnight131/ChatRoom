package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomAdminServiceTest {
    @Test void bindsValidatedIdentityAndRejectsPortIdentityDrift() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), room = UUID.randomUUID();
        AtomicReference<LegacyV1RoomAdminCommand> captured = new AtomicReference<>();
        var service = new LegacyV1RoomAdminService(command -> {
            captured.set(command);
            return new LegacyV1RoomAdminResult.Changed(room, 7, target,
                    "member", "Member", true, true);
        });
        var command = new LegacyV1RoomAdminCommand(actor, 7, "member", true);
        assertInstanceOf(LegacyV1RoomAdminResult.Changed.class, service.change(command));
        assertEquals(command, captured.get());

        var drifting = new LegacyV1RoomAdminService(ignored ->
                new LegacyV1RoomAdminResult.Changed(room, 8, target,
                        "other", "Other", false, true));
        assertThrows(IllegalStateException.class, () -> drifting.change(command));
    }

    @Test void rejectsInvalidRoomAndUsernameBeforePersistence() {
        var service = new LegacyV1RoomAdminService(command -> {
            throw new AssertionError("invalid input reached persistence");
        });
        UUID actor = UUID.randomUUID();
        assertEquals(LegacyV1RoomAdminResult.Rejected.INVALID_INPUT,
                service.change(new LegacyV1RoomAdminCommand(actor, 0, "member", true)));
        assertEquals(LegacyV1RoomAdminResult.Rejected.INVALID_INPUT,
                service.change(new LegacyV1RoomAdminCommand(actor, 7, " member", true)));
        assertEquals(LegacyV1RoomAdminResult.Rejected.INVALID_INPUT,
                service.change(new LegacyV1RoomAdminCommand(actor, 7, "bad\nname", false)));
    }
}
