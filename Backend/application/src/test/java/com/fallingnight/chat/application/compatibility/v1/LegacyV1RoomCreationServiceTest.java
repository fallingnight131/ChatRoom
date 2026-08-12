package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomCreationServiceTest {
    @Test void bindsCreatorHashesPasswordAndClosesSecret() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomCreationIntent> captured = new AtomicReference<>();
        LegacyV1RoomCreationService service = new LegacyV1RoomCreationService(password -> {
            assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), password);
            return encoding();
        }, intent -> {
            captured.set(intent);
            return new LegacyV1RoomCreationResult.Created(
                    conversation, 7, intent.roomName(), intent.actorAccountId(), false);
        });
        var command = command(actor, " request-1 ", "  Project Room  ", "secret");

        var created = (LegacyV1RoomCreationResult.Created) service.create(command);

        assertEquals(actor, created.creatorAccountId()); assertEquals(7, created.legacyRoomId());
        assertEquals("Project Room", captured.get().roomName());
        assertEquals(" request-1 ", captured.get().clientRequestId());
        assertEquals(Optional.of(encoding()), captured.get().encodedPassword());
        assertTrue(command.isClosed());
    }

    @Test void rejectsInvalidInputBeforeHashOrPersistenceAndClosesSecret() {
        AtomicBoolean hashed = new AtomicBoolean(), persisted = new AtomicBoolean();
        LegacyV1RoomCreationService service = new LegacyV1RoomCreationService(password -> {
            hashed.set(true); return encoding();
        }, intent -> {
            persisted.set(true); return LegacyV1RoomCreationResult.Rejected.CREATION_DENIED;
        });
        var shortPassword = command(UUID.randomUUID(), "request", "Room", "abc");
        assertEquals(LegacyV1RoomCreationResult.Rejected.INVALID_INPUT,
                service.create(shortPassword));
        assertTrue(shortPassword.isClosed()); assertFalse(hashed.get()); assertFalse(persisted.get());

        var badName = command(UUID.randomUUID(), "request", "bad\nroom", null);
        assertEquals(LegacyV1RoomCreationResult.Rejected.INVALID_INPUT,
                service.create(badName));
        assertTrue(badName.isClosed()); assertFalse(persisted.get());
    }

    @Test void failsClosedOnPersistenceIdentitySubstitutionAndClosesOnFailure() {
        UUID actor = UUID.randomUUID();
        LegacyV1RoomCreationService substituted = new LegacyV1RoomCreationService(
                password -> encoding(), intent -> new LegacyV1RoomCreationResult.Created(
                        UUID.randomUUID(), 7, intent.roomName(), UUID.randomUUID(), false));
        var command = command(actor, "request", "Room", null);
        assertThrows(IllegalStateException.class, () -> substituted.create(command));
        assertTrue(command.isClosed());

        LegacyV1RoomCreationService failedHash = new LegacyV1RoomCreationService(password -> {
            throw new IllegalStateException("hash failed");
        }, intent -> { throw new AssertionError(); });
        var protectedRoom = command(actor, "request", "Room", "secret");
        assertThrows(IllegalStateException.class, () -> failedHash.create(protectedRoom));
        assertTrue(protectedRoom.isClosed());
    }

    private static LegacyV1RoomCreationCommand command(
            UUID actor, String requestId, String name, String password) {
        return new LegacyV1RoomCreationCommand(actor, requestId, name,
                password == null ? null : password.getBytes(StandardCharsets.UTF_8));
    }
    private static LegacyV1RoomPasswordEncoding encoding() {
        return new LegacyV1RoomPasswordEncoding("$argon2id$encoded", "opaque-hmac-tag");
    }
}
