package com.fallingnight.chat.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.security.SecretBytes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final AccountCredential ENABLED = new AccountCredential(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "Alice",
            "argon2id-hash",
            true);

    @Test
    void establishesSessionOnlyAfterCredentialMatch() {
        IssuedSession issued = issuedSession();
        AtomicReference<ClientDescriptor> issuedFor = new AtomicReference<>();
        AuthenticationService service = service(
                Optional.of(ENABLED),
                (password, hash) -> hash.orElseThrow().equals("argon2id-hash")
                        && new String(password, StandardCharsets.UTF_8).equals("test-password"),
                (account, client, now) -> {
                    assertEquals(ENABLED, account);
                    assertEquals(NOW, now);
                    issuedFor.set(client);
                    return issued;
                });
        AuthenticateCommand command = command();

        AuthenticationResult.Established result = assertInstanceOf(
                AuthenticationResult.Established.class, service.authenticate(command));

        assertSame(issued, result.session());
        assertEquals(ClientPlatform.WEB, issuedFor.get().platform());
        assertTrue(command.isClosed());
        issued.close();
    }

    @Test
    void unknownAccountStillPerformsDummyVerificationAndRejects() {
        AtomicBoolean verified = new AtomicBoolean();
        AtomicBoolean issued = new AtomicBoolean();
        AuthenticationService service = service(
                Optional.empty(),
                (password, hash) -> {
                    assertTrue(hash.isEmpty());
                    verified.set(true);
                    return false;
                },
                (account, client, now) -> {
                    issued.set(true);
                    return issuedSession();
                });
        AuthenticateCommand command = command();

        assertSame(AuthenticationResult.Rejected.INSTANCE, service.authenticate(command));
        assertTrue(verified.get());
        assertFalse(issued.get());
        assertTrue(command.isClosed());
    }

    @Test
    void wrongPasswordAndDisabledAccountShareTheSameRejection() {
        AtomicBoolean issued = new AtomicBoolean();
        SessionIssuePort sessions = (account, client, now) -> {
            issued.set(true);
            return issuedSession();
        };
        AuthenticationService wrongPassword = service(
                Optional.of(ENABLED), (password, hash) -> false, sessions);
        AccountCredential disabled = new AccountCredential(
                ENABLED.accountId(), ENABLED.displayName(), ENABLED.passwordHash(), false);
        AuthenticationService disabledAccount = service(
                Optional.of(disabled), (password, hash) -> true, sessions);

        assertSame(AuthenticationResult.Rejected.INSTANCE,
                wrongPassword.authenticate(command()));
        assertSame(AuthenticationResult.Rejected.INSTANCE,
                disabledAccount.authenticate(command()));
        assertFalse(issued.get());
    }

    private static AuthenticationService service(
            Optional<AccountCredential> account,
            CredentialVerifierPort verifier,
            SessionIssuePort sessions) {
        return new AuthenticationService(
                username -> account,
                verifier,
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AuthenticateCommand command() {
        return new AuthenticateCommand(
                "alice",
                "test-password".getBytes(StandardCharsets.UTF_8),
                new ClientDescriptor("device-1", ClientPlatform.WEB, "0.1.0"));
    }

    private static IssuedSession issuedSession() {
        return new IssuedSession(
                ENABLED.accountId(),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                SecretBytes.copyOf(new byte[32]),
                NOW.plusSeconds(3600),
                ENABLED.displayName());
    }
}
