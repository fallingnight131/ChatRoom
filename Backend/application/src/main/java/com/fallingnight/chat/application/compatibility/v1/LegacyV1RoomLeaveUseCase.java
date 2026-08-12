package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1RoomLeaveUseCase {
    LegacyV1RoomLeaveResult leave(UUID actorAccountId, long legacyRoomId);
}
