package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import com.fallingnight.chat.application.identity.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomJoinServiceTest {
    private static final StoredCredential.Argon2id CREDENTIAL =
            new StoredCredential.Argon2id("$argon2id$encoded");

    @Test void verifiesProtectedRoomAndBindsAtomicJoinSnapshot() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicReference<LegacyV1RoomJoinIntent> captured = new AtomicReference<>();
        LegacyV1RoomJoinPort port = port(candidate(actor, conversation, Optional.of(CREDENTIAL)),
                intent -> {
                    captured.set(intent);
                    return joined(actor, conversation, true);
                });
        LegacyV1RoomJoinService service = new LegacyV1RoomJoinService((password, stored) -> {
            assertArrayEquals(bytes("secret"), password);
            assertEquals(Optional.of(CREDENTIAL), stored);
            return CredentialVerification.VERIFIED;
        }, port);
        var command = command(actor, 77, "secret");

        var result = service.join(command);

        assertEquals(joined(actor, conversation, true), result);
        assertEquals(new LegacyV1RoomJoinIntent(
                actor, conversation, 77, Optional.of(CREDENTIAL)), captured.get());
        assertTrue(command.isClosed());
    }

    @Test void existingMemberIsIdempotentWithoutPasswordOrMutation() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        var existing = joined(actor, conversation, false);
        AtomicBoolean verified = new AtomicBoolean(), mutated = new AtomicBoolean();
        LegacyV1RoomJoinService service = new LegacyV1RoomJoinService((password, stored) -> {
            verified.set(true); return CredentialVerification.REJECTED;
        }, port(new LegacyV1RoomJoinAccess.AlreadyMember(existing), intent -> {
            mutated.set(true); return LegacyV1RoomJoinResult.Rejected.JOIN_DENIED;
        }));
        var command = command(actor, 77, null);

        assertEquals(existing, service.join(command));
        assertFalse(verified.get()); assertFalse(mutated.get()); assertTrue(command.isClosed());
    }

    @Test void distinguishesMissingWrongAndMalformedPasswordsWithoutMutation() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicBoolean mutated = new AtomicBoolean();
        LegacyV1RoomJoinAccess.Candidate access = candidate(
                actor, conversation, Optional.of(CREDENTIAL));
        LegacyV1RoomJoinPort port = port(access, intent -> {
            mutated.set(true); return LegacyV1RoomJoinResult.Rejected.JOIN_DENIED;
        });
        LegacyV1RoomJoinService rejected = new LegacyV1RoomJoinService(
                (password, stored) -> CredentialVerification.REJECTED, port);
        var missing = command(actor, 77, null);
        assertEquals(LegacyV1RoomJoinResult.Rejected.PASSWORD_REQUIRED,
                rejected.join(missing));
        assertTrue(missing.isClosed());

        var wrong = command(actor, 77, "wrong");
        assertEquals(LegacyV1RoomJoinResult.Rejected.INVALID_PASSWORD,
                rejected.join(wrong));
        assertTrue(wrong.isClosed());

        var malformed = new LegacyV1RoomJoinCommand(actor, 77,
                new byte[] {(byte) 0xc3, (byte) 0x28});
        assertEquals(LegacyV1RoomJoinResult.Rejected.INVALID_INPUT,
                rejected.join(malformed));
        assertTrue(malformed.isClosed()); assertFalse(mutated.get());
    }

    @Test void mapsAccessRejectionsAndPreservesAtomicJoinRejections() {
        UUID actor = UUID.randomUUID();
        for (var entry : new Object[][] {
                {LegacyV1RoomJoinAccess.Rejected.NOT_FOUND,
                        LegacyV1RoomJoinResult.Rejected.NOT_FOUND},
                {LegacyV1RoomJoinAccess.Rejected.JOIN_DENIED,
                        LegacyV1RoomJoinResult.Rejected.JOIN_DENIED}}) {
            var service = new LegacyV1RoomJoinService((password, stored) -> fail(),
                    port((LegacyV1RoomJoinAccess) entry[0], intent -> fail()));
            assertEquals(entry[1], service.join(command(actor, 77, null)));
        }
        UUID conversation = UUID.randomUUID();
        var full = new LegacyV1RoomJoinService((password, stored) -> fail(),
                port(candidate(actor, conversation, Optional.empty()),
                        intent -> LegacyV1RoomJoinResult.Rejected.ROOM_FULL));
        assertEquals(LegacyV1RoomJoinResult.Rejected.ROOM_FULL,
                full.join(command(actor, 77, null)));
    }

    @Test void failsClosedOnIdentityOrTargetSubstitutionAndClosesSecret() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        var substitutedAccess = new LegacyV1RoomJoinService((password, stored) -> fail(),
                port(candidate(UUID.randomUUID(), conversation, Optional.empty()),
                        intent -> fail()));
        var first = command(actor, 77, "secret");
        assertThrows(IllegalStateException.class, () -> substitutedAccess.join(first));
        assertTrue(first.isClosed());

        var substitutedResult = new LegacyV1RoomJoinService((password, stored) -> fail(),
                port(candidate(actor, conversation, Optional.empty()), intent ->
                        new LegacyV1RoomJoinResult.Joined(UUID.randomUUID(), 77, "Room",
                                actor, LegacyV1RoomJoinResult.Role.MEMBER, true)));
        var second = command(actor, 77, "secret");
        assertThrows(IllegalStateException.class, () -> substitutedResult.join(second));
        assertTrue(second.isClosed());
    }

    @Test void nullCredentialVerificationFailsClosedWithoutMutation() {
        UUID actor = UUID.randomUUID(), conversation = UUID.randomUUID();
        AtomicBoolean mutated = new AtomicBoolean();
        var service = new LegacyV1RoomJoinService((password, stored) -> null,
                port(candidate(actor, conversation, Optional.of(CREDENTIAL)), intent -> {
                    mutated.set(true); return joined(actor, conversation, true);
                }));
        var command = command(actor, 77, "secret");

        assertThrows(NullPointerException.class, () -> service.join(command));
        assertFalse(mutated.get()); assertTrue(command.isClosed());
    }

    private static LegacyV1RoomJoinAccess.Candidate candidate(UUID actor, UUID conversation,
            Optional<StoredCredential> credential) {
        return new LegacyV1RoomJoinAccess.Candidate(
                conversation, 77, "Room", actor, credential);
    }
    private static LegacyV1RoomJoinResult.Joined joined(
            UUID actor, UUID conversation, boolean newJoin) {
        return new LegacyV1RoomJoinResult.Joined(conversation, 77, "Room", actor,
                LegacyV1RoomJoinResult.Role.MEMBER, newJoin);
    }
    private static LegacyV1RoomJoinPort port(LegacyV1RoomJoinAccess access,
            java.util.function.Function<LegacyV1RoomJoinIntent, LegacyV1RoomJoinResult> join) {
        return new LegacyV1RoomJoinPort() {
            @Override public LegacyV1RoomJoinAccess inspect(UUID actor, long room) {
                return access;
            }
            @Override public LegacyV1RoomJoinResult join(LegacyV1RoomJoinIntent intent) {
                return join.apply(intent);
            }
        };
    }
    private static LegacyV1RoomJoinCommand command(UUID actor, long room, String password) {
        return new LegacyV1RoomJoinCommand(actor, room,
                password == null ? null : bytes(password));
    }
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
