package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomDissolutionServiceTest {
    @Test void bindsActorAndAcceptsFirstAndConvergedResults() {
        UUID actor = UUID.randomUUID(), member = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomDissolutionIntent> captured = new AtomicReference<>();
        var service = new LegacyV1RoomDissolutionService(intent -> {
            captured.set(intent);
            return new LegacyV1RoomDissolutionResult.Dissolved(conversation, 7, "Room",
                    Set.of(actor, member), true, Instant.EPOCH);
        });
        var result = assertInstanceOf(LegacyV1RoomDissolutionResult.Dissolved.class,
                service.dissolve(actor, 7));
        assertTrue(result.changed()); assertEquals(actor, captured.get().actorAccountId());
        assertEquals(7, captured.get().legacyRoomId());

        var retry = new LegacyV1RoomDissolutionService(intent ->
                new LegacyV1RoomDissolutionResult.Dissolved(
                        conversation, 7, "Room", Set.of(), false, Instant.EPOCH));
        assertFalse(assertInstanceOf(LegacyV1RoomDissolutionResult.Dissolved.class,
                retry.dissolve(actor, 7)).changed());
    }

    @Test void rejectsInvalidInputBeforePersistence() {
        var service = new LegacyV1RoomDissolutionService(intent -> {
            throw new AssertionError("invalid dissolution reached persistence");
        });
        UUID actor = UUID.randomUUID();
        assertEquals(LegacyV1RoomDissolutionResult.Rejected.INVALID_INPUT,
                service.dissolve(actor, 0));
        assertEquals(LegacyV1RoomDissolutionResult.Rejected.INVALID_INPUT,
                service.dissolve(actor, (long) Integer.MAX_VALUE + 1));
    }

    @Test void rejectsPersistenceIdentityAndAudienceDrift() {
        UUID actor = UUID.randomUUID();
        var wrongRoom = new LegacyV1RoomDissolutionService(intent ->
                new LegacyV1RoomDissolutionResult.Dissolved(UUID.randomUUID(), 8, "Room",
                        Set.of(actor), true, Instant.EPOCH));
        assertThrows(IllegalStateException.class, () -> wrongRoom.dissolve(actor, 7));

        var missingActor = new LegacyV1RoomDissolutionService(intent ->
                new LegacyV1RoomDissolutionResult.Dissolved(UUID.randomUUID(), 7, "Room",
                        Set.of(UUID.randomUUID()), true, Instant.EPOCH));
        assertThrows(IllegalStateException.class, () -> missingActor.dissolve(actor, 7));
    }
}
