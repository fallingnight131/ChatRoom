package com.fallingnight.chat.application.compatibility.v1;

import java.time.Duration;
import java.util.Objects;

/** Owner-only, retry-idempotent V1 direct recall policy boundary. */
public final class LegacyV1DirectRecallService implements LegacyV1DirectRecallUseCase {
    public static final Duration RECALL_WINDOW = Duration.ofSeconds(120);
    private final LegacyV1DirectRecallPort recalls;

    public LegacyV1DirectRecallService(LegacyV1DirectRecallPort recalls) {
        this.recalls = Objects.requireNonNull(recalls, "recalls");
    }

    @Override public LegacyV1DirectRecallResult recall(LegacyV1DirectRecallCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        if (command.legacyMessageId() <= 0
                || command.legacyMessageId() > Integer.MAX_VALUE) {
            return LegacyV1DirectRecallResult.Rejected.INVALID_MESSAGE_ID;
        }
        LegacyV1DirectRecallResult result = Objects.requireNonNull(
                recalls.recall(command), "direct recall result");
        if (result instanceof LegacyV1DirectRecallResult.Recalled recalled
                && recalled.legacyMessageId() != command.legacyMessageId()) {
            throw new IllegalStateException("direct recall message identity changed");
        }
        return result;
    }
}
