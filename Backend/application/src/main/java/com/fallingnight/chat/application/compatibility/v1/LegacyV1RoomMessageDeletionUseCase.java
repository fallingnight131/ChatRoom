package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1RoomMessageDeletionUseCase {
    LegacyV1RoomMessageDeletionResult delete(LegacyV1RoomMessageDeletionCommand command);
}
