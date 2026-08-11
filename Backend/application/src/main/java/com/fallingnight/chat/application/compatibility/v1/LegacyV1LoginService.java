package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.AuthenticateCommand;
import com.fallingnight.chat.application.identity.AuthenticationResult;
import com.fallingnight.chat.application.identity.IssuedSession;
import java.util.Objects;

/** Adapts server-authoritative authentication to the temporary V1 identity shape. */
public final class LegacyV1LoginService implements LegacyV1LoginUseCase {
    private final LegacyV1AuthenticationService authentication;
    private final LegacyV1AccountProjectionPort identities;

    public LegacyV1LoginService(
            LegacyV1AuthenticationService authentication,
            LegacyV1AccountProjectionPort identities) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public LegacyV1LoginResult login(AuthenticateCommand command) {
        Objects.requireNonNull(command, "command");
        String presentedUsername = command.username();
        AuthenticationResult result = authentication.authenticate(command);
        if (result == AuthenticationResult.Rejected.INSTANCE) {
            return LegacyV1LoginResult.Rejected.INSTANCE;
        }
        AuthenticationResult.Established established =
                (AuthenticationResult.Established) result;
        try (IssuedSession session = established.session()) {
            return identities.findByAccountId(session.accountId())
                    .<LegacyV1LoginResult>map(identity -> new LegacyV1LoginResult.Established(
                            new LegacyV1AuthenticatedIdentity(
                                    identity.legacyUserId(),
                                    session.accountId(),
                                    session.deviceId(),
                                    session.sessionId(),
                                    session.expiresAt(),
                                    presentedUsername,
                                    session.displayName(),
                                    established.credentialUpgradePending())))
                    .orElse(LegacyV1LoginResult.Rejected.INSTANCE);
        }
    }
}
