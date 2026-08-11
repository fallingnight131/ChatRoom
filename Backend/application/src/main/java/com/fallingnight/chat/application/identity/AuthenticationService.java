package com.fallingnight.chat.application.identity;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Transport-independent fresh-login use case with generic rejection semantics. */
public final class AuthenticationService {
    private final AccountCredentialPort accounts;
    private final CredentialVerifierPort verifier;
    private final SessionIssuePort sessions;
    private final Clock clock;

    public AuthenticationService(
            AccountCredentialPort accounts,
            CredentialVerifierPort verifier,
            SessionIssuePort sessions,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AuthenticationResult authenticate(AuthenticateCommand command) {
        Objects.requireNonNull(command, "command");
        try (command) {
            Optional<AccountCredential> account = accounts.findByPresentedUsername(
                    command.username());
            Optional<String> hash = account.map(AccountCredential::passwordHash);
            boolean matches = command.passwordUtf8().withCopy(
                    password -> verifier.matchesOrDummy(password, hash));
            if (!matches || account.isEmpty() || !account.orElseThrow().enabled()) {
                return AuthenticationResult.Rejected.INSTANCE;
            }
            return sessions.issue(account.orElseThrow(), command.client(), clock.instant())
                    .<AuthenticationResult>map(AuthenticationResult.Established::new)
                    .orElse(AuthenticationResult.Rejected.INSTANCE);
        }
    }
}
