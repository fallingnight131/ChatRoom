package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Server-bound actor and requested V1 room title. */
public record LegacyV1RoomRenameCommand(
        UUID actorAccountId, long legacyRoomId, String newName) { }
