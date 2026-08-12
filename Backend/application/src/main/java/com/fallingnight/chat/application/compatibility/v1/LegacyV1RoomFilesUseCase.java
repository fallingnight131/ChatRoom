package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1RoomFilesUseCase {
    LegacyV1RoomFilesResult read(UUID actorAccountId, long legacyRoomId);
}
