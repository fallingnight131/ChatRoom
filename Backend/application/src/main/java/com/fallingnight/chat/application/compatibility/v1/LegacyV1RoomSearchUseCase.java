package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

@FunctionalInterface
public interface LegacyV1RoomSearchUseCase {
    LegacyV1RoomSearchResult search(UUID actorAccountId, String keyword);
}
