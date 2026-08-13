package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

public interface LegacyV1RoomDissolutionUseCase {
    LegacyV1RoomDissolutionResult dissolve(UUID actorAccountId, long legacyRoomId);
}
