package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;
import java.util.UUID;

/** Server-authorized, read-only V1 room-settings compatibility boundary. */
public final class LegacyV1RoomSettingsService implements LegacyV1RoomSettingsUseCase {
    private final LegacyV1RoomSettingsPort settings;

    public LegacyV1RoomSettingsService(LegacyV1RoomSettingsPort settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override public LegacyV1RoomSettingsResult read(UUID actor, long roomId) {
        Objects.requireNonNull(actor, "actor");
        if (roomId <= 0 || roomId > Integer.MAX_VALUE) {
            return LegacyV1RoomSettingsResult.Rejected.INVALID_INPUT;
        }
        var query = Objects.requireNonNull(settings.read(actor, roomId),
                "room settings query result");
        if (query == LegacyV1RoomSettingsPort.QueryResult.Rejected.ROOM_ACCESS_DENIED) {
            return LegacyV1RoomSettingsResult.Rejected.ROOM_ACCESS_DENIED;
        }
        var value = ((LegacyV1RoomSettingsPort.QueryResult.Authorized) query).settings();
        return new LegacyV1RoomSettingsResult.Read(roomId, value);
    }
}
