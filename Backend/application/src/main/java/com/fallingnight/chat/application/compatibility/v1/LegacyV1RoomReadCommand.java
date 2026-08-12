package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Server-bound actor requesting that one mapped V1 room be marked read. */
public record LegacyV1RoomReadCommand(UUID actorAccountId, long legacyRoomId) { }
