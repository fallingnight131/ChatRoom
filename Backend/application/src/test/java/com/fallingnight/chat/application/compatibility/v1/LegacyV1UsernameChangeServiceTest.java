package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1UsernameChangeServiceTest {
    @Test void trimsCompatibleUsernameAndRejectsPersistenceDrift() {
        UUID actor = UUID.randomUUID();
        AtomicReference<LegacyV1UsernameChangeCommand> captured = new AtomicReference<>();
        var service = new LegacyV1UsernameChangeService(command -> {
            captured.set(command);
            return new LegacyV1UsernameChangeResult.Changed(actor, "old_id", "new_id", true,
                    Instant.EPOCH, Instant.EPOCH.plusSeconds(30L * 86400), List.of());
        });
        assertInstanceOf(LegacyV1UsernameChangeResult.Changed.class,
                service.change(new LegacyV1UsernameChangeCommand(actor, "  new_id  ")));
        assertEquals(new LegacyV1UsernameChangeCommand(actor, "new_id"), captured.get());

        var drifting = new LegacyV1UsernameChangeService(command ->
                new LegacyV1UsernameChangeResult.Changed(UUID.randomUUID(), "old_id",
                        "other_id", true, Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(30L * 86400), List.of()));
        assertThrows(IllegalStateException.class, () -> drifting.change(
                new LegacyV1UsernameChangeCommand(actor, "new_id")));
    }

    @Test void rejectsInvalidNamesBeforePersistence() {
        var service = new LegacyV1UsernameChangeService(command -> {
            throw new AssertionError("invalid username reached persistence");
        });
        UUID actor = UUID.randomUUID();
        for (String value : new String[] { null, "short", "has-dash", "a".repeat(21), "用户名123" })
            assertEquals(LegacyV1UsernameChangeResult.Rejected.INVALID_INPUT,
                    service.change(new LegacyV1UsernameChangeCommand(actor, value)));
    }

    @Test void exactRetryCannotCarryPeerEffectsAndActorCannotBeNotifiedAsPeer() {
        UUID actor = UUID.randomUUID();
        Instant changedAt = Instant.EPOCH;
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1UsernameChangeResult.Changed(actor, "new_id", "new_id", false,
                        changedAt, changedAt.plusSeconds(30L * 86400),
                        List.of(new LegacyV1UsernameChangeResult.RoomAudience(
                                7, java.util.Set.of(UUID.randomUUID())))));
        assertThrows(IllegalArgumentException.class, () ->
                new LegacyV1UsernameChangeResult.Changed(actor, "old_id", "new_id", true,
                        changedAt, changedAt.plusSeconds(30L * 86400),
                        List.of(new LegacyV1UsernameChangeResult.RoomAudience(
                                7, java.util.Set.of(actor)))));
    }
}
