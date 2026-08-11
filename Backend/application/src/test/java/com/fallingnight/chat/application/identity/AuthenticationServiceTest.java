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
            new StoredCredential.Argon2id("argon2id-hash"),
            true);

    @Test
    void establishesSessionOnlyAfterCredentialMatch() {
        IssuedSession issued = issuedSession();
        AtomicReference<ClientDescriptor> issuedFor = new AtomicReference<>();
        AuthenticationService service = service(
                Optional.of(ENABLED),
                (password, credential) -> credential.orElseThrow().equals(ENABLED.credential())
                        && new String(password, StandardCharsets.UTF_8).equals("test-password")
                                ? CredentialVerification.VERIFIED
                                : CredentialVerification.REJECTED,
                (account, client, now) -> {
                    assertEquals(ENABLED, account);
                    assertEquals(NOW, now);
                    issuedFor.set(client);
                    return Optional.of(issued);
                });
        AuthenticateCommand command = command();

        AuthenticationResult.Established result = assertInstanceOf(
                AuthenticationResult.Established.class, service.authenticate(command));

        assertSame(issued, result.session());
        assertFalse(result.credentialUpgradePending());
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
                (password, credential) -> {
                    assertTrue(credential.isEmpty());
                    verified.set(true);
                    return CredentialVerification.REJECTED;
                },
                (account, client, now) -> {
                    issued.set(true);
                    return Optional.of(issuedSession());
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
            return Optional.of(issuedSession());
        };
        AuthenticationService wrongPassword = service(
                Optional.of(ENABLED),
                (password, credential) -> CredentialVerification.REJECTED,
                sessions);
        AccountCredential disabled = new AccountCredential(
                ENABLED.accountId(), ENABLED.displayName(), ENABLED.credential(), false);
        AuthenticationService disabledAccount = service(
                Optional.of(disabled),
                (password, credential) -> CredentialVerification.VERIFIED,
                sessions);

        assertSame(AuthenticationResult.Rejected.INSTANCE,
                wrongPassword.authenticate(command()));
        assertSame(AuthenticationResult.Rejected.INSTANCE,
                disabledAccount.authenticate(command()));
        assertFalse(issued.get());
    }

    @Test
    void issuancePolicyDenialUsesTheSameRejection() {
        AuthenticationService service = service(
                Optional.of(ENABLED),
                (password, credential) -> CredentialVerification.VERIFIED,
                (account, client, now) -> Optional.empty());

        assertSame(AuthenticationResult.Rejected.INSTANCE,
                service.authenticate(command()));
    }

    @Test
    void eligibilityDenialOccursAfterVerificationAndBeforeUpgradeOrSession() {
        AtomicBoolean verified = new AtomicBoolean();
        AtomicBoolean upgraded = new AtomicBoolean();
        AtomicBoolean issued = new AtomicBoolean();
        AuthenticationService service = new AuthenticationService(
                username -> Optional.of(ENABLED),
                (password, credential) -> {
                    verified.set(true);
                    return CredentialVerification.VERIFIED_NEEDS_UPGRADE;
                },
                (account, client, now) -> {
                    issued.set(true);
                    return Optional.of(issuedSession());
                },
                password -> new StoredCredential.Argon2id("upgraded-hash"),
                (accountId, expected, replacement) -> {
                    upgraded.set(true);
                    return true;
                },
                account -> false,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertSame(AuthenticationResult.Rejected.INSTANCE, service.authenticate(command()));
        assertTrue(verified.get());
        assertFalse(upgraded.get());
        assertFalse(issued.get());
    }

    @Test
    void legacySuccessUsesCasUpgradeAndReportsPersistenceFailure() {
        StoredCredential.LegacySha256 legacy = new StoredCredential.LegacySha256(
                "a".repeat(64), "legacy-salt");
        AccountCredential legacyAccount = new AccountCredential(
                ENABLED.accountId(), ENABLED.displayName(), legacy, true);
        AtomicReference<StoredCredential> replaced = new AtomicReference<>();
        IssuedSession successfulSession = issuedSession();
        AuthenticationService successful = service(
                Optional.of(legacyAccount),
                (password, credential) -> CredentialVerification.VERIFIED_NEEDS_UPGRADE,
                (account, client, now) -> Optional.of(successfulSession),
                password -> new StoredCredential.Argon2id("new-argon2id"),
                (accountId, expected, replacement) -> {
                    assertEquals(legacy, expected);
                    replaced.set(replacement);
                    return true;
                });
        AuthenticationResult.Established upgraded = assertInstanceOf(
                AuthenticationResult.Established.class,
                successful.authenticate(command()));
        assertEquals(new StoredCredential.Argon2id("new-argon2id"), replaced.get());
        assertFalse(upgraded.credentialUpgradePending());
        upgraded.session().close();

        IssuedSession pendingSession = issuedSession();
        AuthenticationService failedUpgrade = service(
                Optional.of(legacyAccount),
                (password, credential) -> CredentialVerification.VERIFIED_NEEDS_UPGRADE,
                (account, client, now) -> Optional.of(pendingSession),
                password -> new StoredCredential.Argon2id("new-argon2id"),
                (accountId, expected, replacement) -> false);
        AuthenticationResult.Established pending = assertInstanceOf(
                AuthenticationResult.Established.class,
                failedUpgrade.authenticate(command()));
        assertTrue(pending.credentialUpgradePending());
        pending.session().close();
    }

    private static AuthenticationService service(
            Optional<AccountCredential> account,
            CredentialVerifierPort verifier,
            SessionIssuePort sessions) {
        return service(
                account,
                verifier,
                sessions,
                password -> new StoredCredential.Argon2id("upgraded-hash"),
                (accountId, expected, replacement) -> true);
    }

    private static AuthenticationService service(
            Optional<AccountCredential> account,
            CredentialVerifierPort verifier,
            SessionIssuePort sessions,
            CredentialHashPort hasher,
            CredentialUpgradePort upgrades) {
        return new AuthenticationService(
                username -> account,
                verifier,
                sessions,
                hasher,
                upgrades,
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
