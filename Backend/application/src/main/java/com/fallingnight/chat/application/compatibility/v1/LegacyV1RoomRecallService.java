package com.fallingnight.chat.application.compatibility.v1;

import java.time.Duration;
import java.util.Objects;

/** Owner-only, retry-idempotent V1 room recall policy boundary. */
public final class LegacyV1RoomRecallService implements LegacyV1RoomRecallUseCase {
    public static final Duration RECALL_WINDOW = Duration.ofSeconds(120);
    private final LegacyV1RoomRecallPort recalls;

    public LegacyV1RoomRecallService(LegacyV1RoomRecallPort recalls) {
        this.recalls = Objects.requireNonNull(recalls, "recalls");
    }

    @Override public LegacyV1RoomRecallResult recall(LegacyV1RoomRecallCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        if (!positiveInt(command.legacyRoomId()) || !positiveInt(command.legacyMessageId())) {
            return LegacyV1RoomRecallResult.Rejected.INVALID_REQUEST;
        }
        LegacyV1RoomRecallResult result = Objects.requireNonNull(
                recalls.recall(command), "room recall result");
        if (result instanceof LegacyV1RoomRecallResult.Recalled recalled
                && (recalled.legacyRoomId() != command.legacyRoomId()
                    || recalled.legacyMessageId() != command.legacyMessageId())) {
            throw new IllegalStateException("room recall identity changed");
        }
        return result;
    }

    private static boolean positiveInt(long value) {
        return value > 0 && value <= Integer.MAX_VALUE;
    }
}
