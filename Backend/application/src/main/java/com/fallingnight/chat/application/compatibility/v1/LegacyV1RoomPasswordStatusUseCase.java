package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

public interface LegacyV1RoomPasswordStatusUseCase {
    LegacyV1RoomPasswordStatusResult status(UUID actorAccountId, long legacyRoomId);
}
