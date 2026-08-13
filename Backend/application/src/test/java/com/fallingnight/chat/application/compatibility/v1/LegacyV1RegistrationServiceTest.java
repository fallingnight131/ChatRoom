package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.identity.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LegacyV1RegistrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Test void normalizesDisplayHashesBeforePersistenceAndClosesSecret() {
        AtomicReference<LegacyV1RegistrationIntent> captured = new AtomicReference<>();
        var service = service(intent -> {
            captured.set(intent); return new LegacyV1RegistrationPersistenceResult.Created(
                    UUID.randomUUID(), 42, NOW);
        });
        byte[] source = bytes("safe-password");
        var command = new LegacyV1RegistrationCommand("alice_01", "  Alice  ", source);
        var result = assertInstanceOf(LegacyV1RegistrationResult.Registered.class,
                service.register(command));
        assertFalse(result.duplicate()); assertEquals(42, result.legacyUserId());
        assertEquals("Alice", result.displayName()); assertTrue(command.isClosed());
        assertEquals("hash:safe-password", captured.get().credential().encodedHash());
        assertArrayEquals(bytes("safe-password"), source);
    }

    @Test void exactExistingRetryRequiresSameMappedIdentityDisplayAndPassword() {
        UUID account = UUID.randomUUID();
        var exact = service(intent -> new LegacyV1RegistrationPersistenceResult.Existing(
                account, OptionalLong.of(42), "alice_01", "Alice",
                new StoredCredential.Argon2id("stored:safe-password"), NOW));
        var result = assertInstanceOf(LegacyV1RegistrationResult.Registered.class,
                exact.register(command("alice_01", "Alice", "safe-password")));
        assertTrue(result.duplicate()); assertEquals(42, result.legacyUserId());
        assertEquals(LegacyV1RegistrationResult.Rejected.USERNAME_TAKEN,
                exact.register(command("alice_01", "Alice", "wrong-password")));

        var unmapped = service(intent -> new LegacyV1RegistrationPersistenceResult.Existing(
                account, OptionalLong.empty(), "alice_01", "Alice",
                new StoredCredential.Argon2id("stored:safe-password"), NOW));
        assertEquals(LegacyV1RegistrationResult.Rejected.USERNAME_TAKEN,
                unmapped.register(command("alice_01", "Alice", "safe-password")));
    }

    @Test void rejectsInvalidIdentityDisplayAndPasswordBeforeHashing() {
        var service = new LegacyV1RegistrationService(password -> { throw new AssertionError(); },
                (password, credential) -> { throw new AssertionError(); },
                intent -> { throw new AssertionError(); });
        assertRejected(service, command("short", "Alice", "safe-password"));
        assertRejected(service, command("alice-01", "Alice", "safe-password"));
        assertRejected(service, command("alice_01", "   ", "safe-password"));
        assertRejected(service, command("alice_01", "Alice", "abc"));
    }

    private static LegacyV1RegistrationService service(LegacyV1RegistrationPort accounts) {
        return new LegacyV1RegistrationService(password -> new StoredCredential.Argon2id(
                "hash:" + new String(password, StandardCharsets.UTF_8)),
                (password, credential) -> credential.filter(value -> value instanceof
                        StoredCredential.Argon2id argon && argon.encodedHash().equals(
                                "stored:" + new String(password, StandardCharsets.UTF_8))).isPresent()
                        ? CredentialVerification.VERIFIED : CredentialVerification.REJECTED,
                accounts);
    }
    private static LegacyV1RegistrationCommand command(String username, String display,
            String password) {
        return new LegacyV1RegistrationCommand(username, display, bytes(password));
    }
    private static void assertRejected(LegacyV1RegistrationService service,
            LegacyV1RegistrationCommand command) {
        assertEquals(LegacyV1RegistrationResult.Rejected.INVALID_INPUT,
                service.register(command)); assertTrue(command.isClosed());
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
