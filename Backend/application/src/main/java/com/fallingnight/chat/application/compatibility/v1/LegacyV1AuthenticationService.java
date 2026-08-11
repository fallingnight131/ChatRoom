package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.AccountCredentialPort;
import com.fallingnight.chat.application.identity.AuthenticateCommand;
import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.identity.AuthenticationService;
import com.fallingnight.chat.application.identity.AuthenticationUseCase;
import com.fallingnight.chat.application.identity.CredentialHashPort;
import com.fallingnight.chat.application.identity.CredentialUpgradePort;
import com.fallingnight.chat.application.identity.CredentialVerifierPort;
import com.fallingnight.chat.application.identity.SessionIssuePort;
import java.time.Clock;

/** Owns an authentication service whose session issuance is restricted to V1 accounts. */
public final class LegacyV1AuthenticationService implements AuthenticationUseCase {
    private final AuthenticationService delegate;

    public LegacyV1AuthenticationService(
            AccountCredentialPort accounts,
            CredentialVerifierPort verifier,
            SessionIssuePort sessions,
            CredentialHashPort hasher,
            CredentialUpgradePort upgrades,
            LegacyV1AccountProjectionPort identities,
            Clock clock) {
        delegate = new AuthenticationService(
                accounts,
                verifier,
                sessions,
                hasher,
                upgrades,
                new LegacyV1AuthenticationEligibilityPolicy(identities),
                clock);
    }

    @Override
    public AuthenticationResult authenticate(AuthenticateCommand command) {
        return delegate.authenticate(command);
    }
}
