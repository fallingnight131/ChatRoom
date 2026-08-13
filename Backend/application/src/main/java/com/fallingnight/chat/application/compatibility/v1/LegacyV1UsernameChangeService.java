package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validates a server-authoritative, cooldown-bound V1 login-name mutation. */
public final class LegacyV1UsernameChangeService implements LegacyV1UsernameChangeUseCase {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{6,20}");
    private final LegacyV1UsernameChangePort accounts;

    public LegacyV1UsernameChangeService(LegacyV1UsernameChangePort accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    static boolean validUsername(String username) {
        return username != null && USERNAME.matcher(username).matches();
    }

    @Override public LegacyV1UsernameChangeResult change(
            LegacyV1UsernameChangeCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        String normalized = command.newUsername() == null ? null : command.newUsername().strip();
        if (!validUsername(normalized))
            return LegacyV1UsernameChangeResult.Rejected.INVALID_INPUT;
        var normalizedCommand = new LegacyV1UsernameChangeCommand(
                command.actorAccountId(), normalized);
        LegacyV1UsernameChangeResult result = Objects.requireNonNull(
                accounts.change(normalizedCommand), "username change result");
        if (result instanceof LegacyV1UsernameChangeResult.Changed changed
                && (!changed.accountId().equals(command.actorAccountId())
                    || !changed.newUsername().equals(normalized)))
            throw new IllegalStateException("V1 username change identity changed");
        return result;
    }
}
