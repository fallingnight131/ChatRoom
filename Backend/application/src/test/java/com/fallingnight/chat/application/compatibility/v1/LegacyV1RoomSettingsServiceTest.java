package com.fallingnight.chat.application.compatibility.v1;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class LegacyV1RoomSettingsServiceTest {
    @Test void bindsActorAndReturnsExactAuthorizedSettings() {
        UUID actor = UUID.randomUUID();
        var expected = new LegacyV1RoomSettings(1024, 4096, 17, 23);
        var service = new LegacyV1RoomSettingsService((actual, room) -> {
            assertEquals(actor, actual); assertEquals(77, room);
            return new LegacyV1RoomSettingsPort.QueryResult.Authorized(expected);
        });

        var read = (LegacyV1RoomSettingsResult.Read) service.read(actor, 77);
        assertEquals(77, read.legacyRoomId()); assertEquals(expected, read.settings());
    }

    @Test void rejectsInvalidAndUnauthorizedRooms() {
        UUID actor = UUID.randomUUID();
        var invalid = new LegacyV1RoomSettingsService((a, r) -> fail());
        assertEquals(LegacyV1RoomSettingsResult.Rejected.INVALID_INPUT,
                invalid.read(actor, 0));
        var denied = new LegacyV1RoomSettingsService((a, r) ->
                LegacyV1RoomSettingsPort.QueryResult.Rejected.ROOM_ACCESS_DENIED);
        assertEquals(LegacyV1RoomSettingsResult.Rejected.ROOM_ACCESS_DENIED,
                denied.read(actor, 77));
    }

    @Test void enforcesCompatibleSettingsInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomSettings(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomSettings(2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomSettings(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new LegacyV1RoomSettings(1, 1, 1, 1_000_001));
        assertThrows(IllegalArgumentException.class, () -> new LegacyV1RoomSettings(
                LegacyV1RoomSettings.MAX_JSON_SAFE_INTEGER + 1,
                LegacyV1RoomSettings.MAX_JSON_SAFE_INTEGER + 1, 1, 1));
    }
}
