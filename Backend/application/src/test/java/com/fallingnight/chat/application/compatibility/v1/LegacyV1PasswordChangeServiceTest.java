package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.identity.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1PasswordChangeServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final StoredCredential.Argon2id CURRENT =
            new StoredCredential.Argon2id("current-password");

    @Test void verifiesHashesPersistsAndClosesOwnedSecrets() {
        UUID actor = UUID.randomUUID(), session = UUID.randomUUID();
        AtomicReference<LegacyV1PasswordChangeIntent> captured = new AtomicReference<>();
        var service = new LegacyV1PasswordChangeService(verifier(), password ->
                new StoredCredential.Argon2id(new String(password, StandardCharsets.UTF_8)),
                new LegacyV1PasswordChangePort() {
                    @Override public LegacyV1PasswordChangeAccess inspect(UUID account, UUID current) {
                        assertEquals(actor, account); assertEquals(session, current);
                        return new LegacyV1PasswordChangeAccess.Candidate(CURRENT, Instant.EPOCH);
                    }
                    @Override public LegacyV1PasswordChangePersistenceResult replace(
                            LegacyV1PasswordChangeIntent intent) {
                        captured.set(intent);
                        return new LegacyV1PasswordChangePersistenceResult.Updated(2, NOW);
                    }
                });
        byte[] oldValue = bytes("current-password"), newValue = bytes("new-password");
        var command = new LegacyV1PasswordChangeCommand(actor, session, oldValue, newValue);
        var changed = assertInstanceOf(LegacyV1PasswordChangeResult.Changed.class,
                service.change(command));
        assertTrue(changed.changed()); assertEquals(2, changed.otherSessionsRevoked());
        assertEquals(NOW, changed.changedAt()); assertTrue(command.isClosed());
        assertEquals(CURRENT, captured.get().expectedCredential());
        assertEquals("new-password", captured.get().replacementCredential().encodedHash());
        assertArrayEquals(bytes("current-password"), oldValue);
        assertArrayEquals(bytes("new-password"), newValue);
    }

    @Test void exactRetryConvergesWhenNewPasswordIsAlreadyCurrent() {
        AtomicBoolean hashed = new AtomicBoolean();
        var service = service(new StoredCredential.Argon2id("new-password"), password -> {
            hashed.set(true); throw new AssertionError();
        }, intent -> { throw new AssertionError(); });
        var result = assertInstanceOf(LegacyV1PasswordChangeResult.Changed.class,
                service.change(command("old-password", "new-password")));
        assertFalse(result.changed()); assertEquals(Instant.EPOCH, result.changedAt());
        assertFalse(hashed.get());
    }

    @Test void rejectsWrongSessionWrongPasswordAndInvalidUtf8WithoutMutation() {
        var invalidSession = new LegacyV1PasswordChangeService(verifier(), password -> {
            throw new AssertionError();
        }, new LegacyV1PasswordChangePort() {
            @Override public LegacyV1PasswordChangeAccess inspect(UUID account, UUID session) {
                return LegacyV1PasswordChangeAccess.Rejected.SESSION_INVALID;
            }
            @Override public LegacyV1PasswordChangePersistenceResult replace(
                    LegacyV1PasswordChangeIntent intent) { throw new AssertionError(); }
        });
        assertEquals(LegacyV1PasswordChangeResult.Rejected.SESSION_INVALID,
                invalidSession.change(command("current-password", "new-password")));

        var wrong = service(CURRENT, password -> { throw new AssertionError(); },
                intent -> { throw new AssertionError(); });
        assertEquals(LegacyV1PasswordChangeResult.Rejected.CURRENT_PASSWORD_INCORRECT,
                wrong.change(command("wrong-password", "new-password")));
        assertEquals(LegacyV1PasswordChangeResult.Rejected.INVALID_INPUT,
                wrong.change(command("current-password", "abc")));
        var malformed = new LegacyV1PasswordChangeCommand(UUID.randomUUID(), UUID.randomUUID(),
                bytes("current-password"), new byte[] {(byte) 0xc3, (byte) 0x28});
        assertEquals(LegacyV1PasswordChangeResult.Rejected.INVALID_INPUT,
                wrong.change(malformed)); assertTrue(malformed.isClosed());
    }

    @Test void mapsCompareAndSetConflictWithoutClaimingSuccess() {
        var service = service(CURRENT,
                password -> new StoredCredential.Argon2id("replacement"),
                intent -> LegacyV1PasswordChangePersistenceResult.Rejected.CONCURRENT_CHANGE);
        assertEquals(LegacyV1PasswordChangeResult.Rejected.CONCURRENT_CHANGE,
                service.change(command("current-password", "new-password")));
    }

    private static LegacyV1PasswordChangeService service(StoredCredential credential,
            CredentialHashPort hasher, java.util.function.Function<LegacyV1PasswordChangeIntent,
                    LegacyV1PasswordChangePersistenceResult> replace) {
        return new LegacyV1PasswordChangeService(verifier(), hasher,
                new LegacyV1PasswordChangePort() {
                    @Override public LegacyV1PasswordChangeAccess inspect(UUID account, UUID session) {
                        return new LegacyV1PasswordChangeAccess.Candidate(credential, Instant.EPOCH);
                    }
                    @Override public LegacyV1PasswordChangePersistenceResult replace(
                            LegacyV1PasswordChangeIntent intent) { return replace.apply(intent); }
                });
    }
    private static CredentialVerifierPort verifier() {
        return (password, credential) -> credential.filter(value -> value instanceof
                StoredCredential.Argon2id argon && argon.encodedHash().equals(
                        new String(password, StandardCharsets.UTF_8))).isPresent()
                ? CredentialVerification.VERIFIED : CredentialVerification.REJECTED;
    }
    private static LegacyV1PasswordChangeCommand command(String oldValue, String newValue) {
        return new LegacyV1PasswordChangeCommand(UUID.randomUUID(), UUID.randomUUID(),
                bytes(oldValue), bytes(newValue));
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
