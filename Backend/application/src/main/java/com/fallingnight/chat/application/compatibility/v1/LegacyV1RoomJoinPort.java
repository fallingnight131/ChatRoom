package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Authoritative room lookup plus atomic, compare-snapshot membership mutation. */
public interface LegacyV1RoomJoinPort {
    LegacyV1RoomJoinAccess inspect(UUID actorAccountId, long legacyRoomId);
    LegacyV1RoomJoinResult join(LegacyV1RoomJoinIntent intent);
}
