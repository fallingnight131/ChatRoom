package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Validates server-bound V1 room moderation before atomic persistence. */
public final class LegacyV1RoomKickService implements LegacyV1RoomKickUseCase {
    public static final int MAX_USERNAME_UTF8_BYTES = 128;
    private final LegacyV1RoomKickPort rooms;

    public LegacyV1RoomKickService(LegacyV1RoomKickPort rooms) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomKickResult kick(LegacyV1RoomKickCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        if (command.legacyRoomId() <= 0 || command.legacyRoomId() > Integer.MAX_VALUE
                || !validUsername(command.targetUsername())) {
            return LegacyV1RoomKickResult.Rejected.INVALID_INPUT;
        }
        LegacyV1RoomKickResult result = Objects.requireNonNull(
                rooms.kick(command), "room kick result");
        if (result instanceof LegacyV1RoomKickResult.Kicked kicked
                && (kicked.legacyRoomId() != command.legacyRoomId()
                    || !kicked.targetUsername().equals(command.targetUsername()))) {
            throw new IllegalStateException("V1 room kick identity changed");
        }
        return result;
    }

    private static boolean validUsername(String value) {
        return value != null && !value.isEmpty() && value.equals(value.strip())
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_USERNAME_UTF8_BYTES
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
