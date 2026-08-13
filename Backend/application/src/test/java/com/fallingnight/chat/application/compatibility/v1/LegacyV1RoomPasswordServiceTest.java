package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomPasswordServiceTest {
    @Test void hashesSetClearsSecretsAndBindsIdentity() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomPasswordIntent> captured = new AtomicReference<>();
        var service = new LegacyV1RoomPasswordService(password -> {
            assertArrayEquals("安全密码".getBytes(StandardCharsets.UTF_8), password);
            return new LegacyV1RoomPasswordEncoding("$argon2id$encoded", "hmac-tag");
        }, port(captured, conversation));
        byte[] source = "安全密码".getBytes(StandardCharsets.UTF_8);
        var set = new LegacyV1RoomPasswordCommand(actor, 7, source);
        assertInstanceOf(LegacyV1RoomPasswordUpdateResult.Updated.class, service.update(set));
        assertTrue(set.isClosed()); assertArrayEquals("安全密码".getBytes(StandardCharsets.UTF_8), source);
        assertTrue(captured.get().encodedPassword().isPresent());

        var clear = new LegacyV1RoomPasswordCommand(actor, 7, new byte[0]);
        assertInstanceOf(LegacyV1RoomPasswordUpdateResult.Updated.class, service.update(clear));
        assertTrue(clear.isClosed()); assertEquals(Optional.empty(), captured.get().encodedPassword());
    }

    @Test void validatesStatusAndPasswordBeforePersistence() {
        var service = new LegacyV1RoomPasswordService(password -> {
            throw new AssertionError("invalid password was hashed");
        }, new LegacyV1RoomPasswordPort() {
            @Override public LegacyV1RoomPasswordStatusResult status(UUID actor, long room) {
                throw new AssertionError("invalid status reached persistence");
            }
            @Override public LegacyV1RoomPasswordUpdateResult update(
                    LegacyV1RoomPasswordIntent intent) {
                throw new AssertionError("invalid update reached persistence");
            }
        });
        UUID actor = UUID.randomUUID();
        assertEquals(LegacyV1RoomPasswordStatusResult.Rejected.INVALID_INPUT,
                service.status(actor, 0));
        var shortPassword = new LegacyV1RoomPasswordCommand(
                actor, 7, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(LegacyV1RoomPasswordUpdateResult.Rejected.INVALID_INPUT,
                service.update(shortPassword));
        assertTrue(shortPassword.isClosed());
        var malformed = new LegacyV1RoomPasswordCommand(actor, 7, new byte[]{(byte) 0xff});
        assertEquals(LegacyV1RoomPasswordUpdateResult.Rejected.INVALID_INPUT,
                service.update(malformed));
        assertTrue(malformed.isClosed());
    }

    @Test void rejectsPersistenceIdentityDrift() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        var service = new LegacyV1RoomPasswordService(password ->
                new LegacyV1RoomPasswordEncoding("$argon2id$encoded", "tag"),
                new LegacyV1RoomPasswordPort() {
                    @Override public LegacyV1RoomPasswordStatusResult status(UUID id, long room) {
                        return new LegacyV1RoomPasswordStatusResult.Authorized(
                                conversation, 8, false, Instant.EPOCH);
                    }
                    @Override public LegacyV1RoomPasswordUpdateResult update(
                            LegacyV1RoomPasswordIntent intent) {
                        return new LegacyV1RoomPasswordUpdateResult.Updated(
                                conversation, 8, true, true, Instant.EPOCH);
                    }
                });
        assertThrows(IllegalStateException.class, () -> service.status(actor, 7));
        assertThrows(IllegalStateException.class, () -> service.update(
                new LegacyV1RoomPasswordCommand(actor, 7,
                        "password".getBytes(StandardCharsets.UTF_8))));
    }

    private static LegacyV1RoomPasswordPort port(
            AtomicReference<LegacyV1RoomPasswordIntent> captured, UUID conversation) {
        return new LegacyV1RoomPasswordPort() {
            @Override public LegacyV1RoomPasswordStatusResult status(UUID actor, long room) {
                return new LegacyV1RoomPasswordStatusResult.Authorized(
                        conversation, room, false, Instant.EPOCH);
            }
            @Override public LegacyV1RoomPasswordUpdateResult update(
                    LegacyV1RoomPasswordIntent intent) {
                captured.set(intent);
                return new LegacyV1RoomPasswordUpdateResult.Updated(conversation,
                        intent.legacyRoomId(), intent.encodedPassword().isPresent(), true,
                        Instant.EPOCH);
            }
        };
    }
}
