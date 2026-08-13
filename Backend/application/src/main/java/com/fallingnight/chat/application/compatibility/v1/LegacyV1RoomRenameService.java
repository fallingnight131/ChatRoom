package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** Normalizes and validates a convergent V1 room-title mutation. */
public final class LegacyV1RoomRenameService implements LegacyV1RoomRenameUseCase {
    public static final int MAX_NAME_CODE_POINTS = 100;
    private final LegacyV1RoomRenamePort rooms;

    public LegacyV1RoomRenameService(LegacyV1RoomRenamePort rooms) {
        this.rooms = Objects.requireNonNull(rooms, "rooms");
    }

    @Override public LegacyV1RoomRenameResult rename(LegacyV1RoomRenameCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        String normalized = command.newName() == null ? null : command.newName().strip();
        if (command.legacyRoomId() <= 0 || command.legacyRoomId() > Integer.MAX_VALUE
                || normalized == null || normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length()) > MAX_NAME_CODE_POINTS
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return LegacyV1RoomRenameResult.Rejected.INVALID_INPUT;
        }
        var normalizedCommand = new LegacyV1RoomRenameCommand(
                command.actorAccountId(), command.legacyRoomId(), normalized);
        LegacyV1RoomRenameResult result = Objects.requireNonNull(
                rooms.rename(normalizedCommand), "room rename result");
        if (result instanceof LegacyV1RoomRenameResult.Renamed renamed
                && (renamed.legacyRoomId() != command.legacyRoomId()
                    || !renamed.newName().equals(normalized))) {
            throw new IllegalStateException("V1 room rename identity changed");
        }
        return result;
    }
}
