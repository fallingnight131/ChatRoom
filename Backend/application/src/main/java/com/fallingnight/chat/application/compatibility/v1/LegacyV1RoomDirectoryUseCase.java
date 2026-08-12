package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface LegacyV1RoomDirectoryUseCase {
    List<LegacyV1RoomSummary> listRooms(UUID accountId);
}
