package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1RoomMemberListUseCase {
    LegacyV1RoomMemberListResult list(UUID actorAccountId, long legacyRoomId);
}
