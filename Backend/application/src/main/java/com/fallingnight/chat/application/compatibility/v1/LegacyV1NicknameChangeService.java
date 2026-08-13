package com.fallingnight.chat.application.compatibility.v1;

import java.text.Normalizer;
import java.util.Objects;

/** Normalizes and validates a convergent V1 display-name mutation. */
public final class LegacyV1NicknameChangeService implements LegacyV1NicknameChangeUseCase {
    public static final int MAX_DISPLAY_NAME_CODE_POINTS = 20;
    private final LegacyV1NicknameChangePort accounts;

    public LegacyV1NicknameChangeService(LegacyV1NicknameChangePort accounts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override public LegacyV1NicknameChangeResult change(
            LegacyV1NicknameChangeCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        String normalized = command.newDisplayName() == null ? null
                : Normalizer.normalize(command.newDisplayName().strip(), Normalizer.Form.NFC);
        if (normalized == null || normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length())
                    > MAX_DISPLAY_NAME_CODE_POINTS
                || normalized.codePoints().anyMatch(Character::isISOControl))
            return LegacyV1NicknameChangeResult.Rejected.INVALID_INPUT;
        var normalizedCommand = new LegacyV1NicknameChangeCommand(
                command.actorAccountId(), normalized);
        LegacyV1NicknameChangeResult result = Objects.requireNonNull(
                accounts.change(normalizedCommand), "nickname change result");
        if (result instanceof LegacyV1NicknameChangeResult.Changed changed
                && (!changed.accountId().equals(command.actorAccountId())
                    || !changed.newDisplayName().equals(normalized)))
            throw new IllegalStateException("V1 nickname change identity changed");
        return result;
    }
}
