package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Server-bound actor requesting one mapped V1 friendship be marked read. */
public record LegacyV1DirectReadCommand(UUID actorAccountId, long legacyFriendshipId) { }
