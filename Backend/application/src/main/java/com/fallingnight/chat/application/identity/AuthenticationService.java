package com.fallingnight.chat.application.identity;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/** Transport-independent fresh-login use case with generic rejection semantics. */
public final class AuthenticationService implements AuthenticationUseCase {
    private final AccountCredentialPort accounts;
    private final CredentialVerifierPort verifier;
    private final SessionIssuePort sessions;
    private final CredentialHashPort hasher;
    private final CredentialUpgradePort upgrades;
    private final Clock clock;

    public AuthenticationService(
            AccountCredentialPort accounts,
            CredentialVerifierPort verifier,
            SessionIssuePort sessions,
            CredentialHashPort hasher,
            CredentialUpgradePort upgrades,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.upgrades = Objects.requireNonNull(upgrades, "upgrades");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AuthenticationResult authenticate(AuthenticateCommand command) {
        Objects.requireNonNull(command, "command");
        try (command) {
            Optional<AccountCredential> account = accounts.findByPresentedUsername(
                    command.username());
            Optional<StoredCredential> credential = account.map(AccountCredential::credential);
            CredentialVerification verification = command.passwordUtf8().withCopy(
                    password -> verifier.verifyOrDummy(password, credential));
            if (verification == CredentialVerification.REJECTED
                    || account.isEmpty()
                    || !account.orElseThrow().enabled()) {
                return AuthenticationResult.Rejected.INSTANCE;
            }
            boolean upgradePending = false;
            if (verification == CredentialVerification.VERIFIED_NEEDS_UPGRADE) {
                try {
                    StoredCredential expected = credential.orElseThrow();
                    StoredCredential.Argon2id replacement = command.passwordUtf8().withCopy(
                            hasher::hash);
                    upgradePending = !upgrades.replace(
                            account.orElseThrow().accountId(), expected, replacement);
                } catch (RuntimeException exception) {
                    upgradePending = true;
                }
            }
            boolean finalUpgradePending = upgradePending;
            return sessions.issue(account.orElseThrow(), command.client(), clock.instant())
                    .<AuthenticationResult>map(session -> new AuthenticationResult.Established(
                            session, finalUpgradePending))
                    .orElse(AuthenticationResult.Rejected.INSTANCE);
        }
    }
}
