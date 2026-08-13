package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

public interface LegacyV1RoomPasswordPort {
    LegacyV1RoomPasswordStatusResult status(UUID actorAccountId, long legacyRoomId);
    LegacyV1RoomPasswordUpdateResult update(LegacyV1RoomPasswordIntent intent);
}
