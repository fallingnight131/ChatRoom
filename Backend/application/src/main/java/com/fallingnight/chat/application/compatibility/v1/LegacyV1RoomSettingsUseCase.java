package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1RoomSettingsUseCase {
    LegacyV1RoomSettingsResult read(UUID actorAccountId, long legacyRoomId);
}
