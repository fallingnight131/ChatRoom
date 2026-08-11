package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.identity.AccountCredential;
import com.fallingnight.chat.application.identity.AuthenticateCommand;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.application.identity.CredentialVerification;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.identity.SessionIssuePort;
import com.fallingnight.chat.application.identity.StoredCredential;
import com.fallingnight.chat.application.security.SecretBytes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LegacyV1LoginServiceTest {
    private static final UUID ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void projectsEstablishedSessionToV1IdentityAndClearsResumeSecret() {
        SecretBytes token = SecretBytes.copyOf(new byte[32]);
        IssuedSession session = issuedSession(token);
        LegacyV1AccountProjectionPort projection = projection(
                new LegacyV1AccountIdentity(17, ACCOUNT_ID));
        LegacyV1LoginService service = new LegacyV1LoginService(
                authentication(projection, session, true), projection);

        LegacyV1LoginResult.Established result = assertInstanceOf(
                LegacyV1LoginResult.Established.class, service.login(command()));

        assertEquals(17, result.identity().legacyUserId());
        assertEquals(ACCOUNT_ID, result.identity().accountId());
        assertEquals("alice", result.identity().username());
        assertEquals("Alice", result.identity().displayName());
        assertTrue(result.identity().credentialUpgradePending());
        assertTrue(token.isClosed());
    }

    @Test
    void missingMappingUsesGenericRejectionBeforeSessionIssue() {
        AtomicBoolean issued = new AtomicBoolean();
        LegacyV1AccountProjectionPort projection = projection(null);
        LegacyV1LoginService service = new LegacyV1LoginService(
                authentication(projection, (account, client, now) -> {
                    issued.set(true);
                    return Optional.of(issuedSession(SecretBytes.copyOf(new byte[32])));
                }, false),
                projection);

        assertSame(LegacyV1LoginResult.Rejected.INSTANCE, service.login(command()));
        assertFalse(issued.get());
    }

    @Test
    void eligibilityRequiresAnExactMapping() {
        AccountCredential account = new AccountCredential(
                ACCOUNT_ID, "Alice", new StoredCredential.Argon2id("hash"), true);
        LegacyV1AuthenticationEligibilityPolicy allowed =
                new LegacyV1AuthenticationEligibilityPolicy(projection(
                        new LegacyV1AccountIdentity(17, ACCOUNT_ID)));
        LegacyV1AuthenticationEligibilityPolicy denied =
                new LegacyV1AuthenticationEligibilityPolicy(projection(null));

        assertTrue(allowed.mayEstablish(account));
        assertFalse(denied.mayEstablish(account));
    }

    @Test
    void authenticationRejectionDoesNotReadProjection() {
        AtomicBoolean projectionRead = new AtomicBoolean();
        LegacyV1AccountProjectionPort projection = new LegacyV1AccountProjectionPort() {
            @Override
            public Optional<LegacyV1AccountIdentity> findByPresentedUsername(
                    String username) {
                projectionRead.set(true);
                return Optional.empty();
            }

            @Override
            public Optional<LegacyV1AccountIdentity> findByAccountId(UUID accountId) {
                projectionRead.set(true);
                return Optional.empty();
            }
        };
        LegacyV1LoginService service = new LegacyV1LoginService(
                new LegacyV1AuthenticationService(
                        username -> Optional.empty(),
                        (password, credential) -> CredentialVerification.REJECTED,
                        (account, client, now) -> Optional.empty(),
                        password -> new StoredCredential.Argon2id("unused"),
                        (accountId, expected, replacement) -> false,
                        projection,
                        Clock.fixed(Instant.parse("2026-08-12T11:00:00Z"), ZoneOffset.UTC)),
                projection);

        assertSame(LegacyV1LoginResult.Rejected.INSTANCE, service.login(command()));
        assertFalse(projectionRead.get());
    }

    private static LegacyV1AccountProjectionPort projection(
            LegacyV1AccountIdentity identity) {
        return new LegacyV1AccountProjectionPort() {
            @Override
            public Optional<LegacyV1AccountIdentity> findByPresentedUsername(String username) {
                return Optional.ofNullable(identity);
            }

            @Override
            public Optional<LegacyV1AccountIdentity> findByAccountId(UUID accountId) {
                return Optional.ofNullable(identity)
                        .filter(value -> value.accountId().equals(accountId));
            }
        };
    }

    private static LegacyV1AuthenticationService authentication(
            LegacyV1AccountProjectionPort projection,
            IssuedSession session,
            boolean upgradePending) {
        return authentication(
                projection,
                (account, client, now) -> Optional.of(session),
                upgradePending);
    }

    private static LegacyV1AuthenticationService authentication(
            LegacyV1AccountProjectionPort projection,
            SessionIssuePort sessions,
            boolean upgradePending) {
        return new LegacyV1AuthenticationService(
                username -> Optional.of(new AccountCredential(
                        ACCOUNT_ID,
                        "Alice",
                        new StoredCredential.Argon2id("hash"),
                        true)),
                (password, credential) -> upgradePending
                        ? CredentialVerification.VERIFIED_NEEDS_UPGRADE
                        : CredentialVerification.VERIFIED,
                sessions,
                password -> new StoredCredential.Argon2id("upgraded"),
                (accountId, expected, replacement) -> !upgradePending,
                projection,
                Clock.fixed(Instant.parse("2026-08-12T11:00:00Z"), ZoneOffset.UTC));
    }

    private static AuthenticateCommand command() {
        return new AuthenticateCommand(
                "alice",
                "password".getBytes(StandardCharsets.UTF_8),
                new ClientDescriptor("v1-web-device", ClientPlatform.WEB, "v1"));
    }

    private static IssuedSession issuedSession(SecretBytes token) {
        return new IssuedSession(
                ACCOUNT_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                token,
                Instant.parse("2026-08-12T12:00:00Z"),
                "Alice");
    }
}
