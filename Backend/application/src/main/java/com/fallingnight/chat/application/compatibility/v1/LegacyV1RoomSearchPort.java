package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface LegacyV1RoomSearchPort {
    List<LegacyV1RoomSearchEntry> search(UUID actorAccountId, String keyword, int limit);
}
