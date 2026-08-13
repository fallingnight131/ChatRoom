package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomKickServiceTest {
    @Test void bindsValidatedIdentityAndRejectsPersistenceDrift() {
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomKickCommand> captured = new AtomicReference<>();
        var service = new LegacyV1RoomKickService(command -> {
            captured.set(command);
            return new LegacyV1RoomKickResult.Kicked(conversation, 7, "Room", target,
                    "member", "Member", true, Instant.EPOCH);
        });
        var command = new LegacyV1RoomKickCommand(actor, 7, "member");
        assertInstanceOf(LegacyV1RoomKickResult.Kicked.class, service.kick(command));
        assertEquals(command, captured.get());

        var drifting = new LegacyV1RoomKickService(ignored ->
                new LegacyV1RoomKickResult.Kicked(conversation, 8, "Other", target,
                        "other", "Other", true, Instant.EPOCH));
        assertThrows(IllegalStateException.class, () -> drifting.kick(command));
    }

    @Test void rejectsInvalidInputBeforePersistence() {
        var service = new LegacyV1RoomKickService(command -> {
            throw new AssertionError("invalid input reached persistence");
        });
        UUID actor = UUID.randomUUID();
        assertEquals(LegacyV1RoomKickResult.Rejected.INVALID_INPUT,
                service.kick(new LegacyV1RoomKickCommand(actor, 0, "member")));
        assertEquals(LegacyV1RoomKickResult.Rejected.INVALID_INPUT,
                service.kick(new LegacyV1RoomKickCommand(actor, 7, " member")));
        assertEquals(LegacyV1RoomKickResult.Rejected.INVALID_INPUT,
                service.kick(new LegacyV1RoomKickCommand(actor, 7, "bad\nname")));
    }
}
