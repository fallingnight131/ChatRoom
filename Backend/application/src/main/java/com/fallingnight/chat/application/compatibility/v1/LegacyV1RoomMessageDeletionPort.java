package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1RoomMessageDeletionPort {
    LegacyV1RoomMessageDeletionResult delete(LegacyV1RoomMessageDeletionIntent intent);
}
