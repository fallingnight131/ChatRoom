package com.fallingnight.chat.application.compatibility.v1;

import java.util.Optional;
import java.util.UUID;

/** Canonical credential replacement; empty encoding removes admission password. */
public record LegacyV1RoomPasswordIntent(
        UUID actorAccountId,
        long legacyRoomId,
        Optional<LegacyV1RoomPasswordEncoding> encodedPassword) { }
