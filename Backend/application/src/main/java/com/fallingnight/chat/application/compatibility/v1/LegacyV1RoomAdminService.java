package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Validates the V1 administrator command before atomic authorization and mutation. */
public final class LegacyV1RoomAdminService implements LegacyV1RoomAdminUseCase {
    public static final int MAX_USERNAME_UTF8_BYTES = 128;
    private final LegacyV1RoomAdminPort rooms;

    public LegacyV1RoomAdminService(LegacyV1RoomAdminPort rooms) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomAdminResult change(LegacyV1RoomAdminCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        if (command.legacyRoomId() <= 0 || command.legacyRoomId() > Integer.MAX_VALUE
                || !validUsername(command.targetUsername())) {
            return LegacyV1RoomAdminResult.Rejected.INVALID_INPUT;
        }
        LegacyV1RoomAdminResult result = Objects.requireNonNull(
                rooms.change(command), "room administrator result");
        if (result instanceof LegacyV1RoomAdminResult.Changed changed
                && (changed.legacyRoomId() != command.legacyRoomId()
                    || !changed.targetUsername().equals(command.targetUsername())
                    || changed.admin() != command.admin())) {
            throw new IllegalStateException("V1 room administrator identity changed");
        }
        return result;
    }

    private static boolean validUsername(String value) {
        return value != null && !value.isEmpty() && value.equals(value.strip())
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_USERNAME_UTF8_BYTES
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
