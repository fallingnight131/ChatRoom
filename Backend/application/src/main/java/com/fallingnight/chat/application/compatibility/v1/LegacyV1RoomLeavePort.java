package com.fallingnight.chat.application.compatibility.v1;

/** Atomically ends membership, transfers ownership, or dissolves the last-member room. */
@FunctionalInterface
public interface LegacyV1RoomLeavePort {
    LegacyV1RoomLeaveResult leave(LegacyV1RoomLeaveIntent intent);
}
