package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1RoomRenamePort {
    LegacyV1RoomRenameResult rename(LegacyV1RoomRenameCommand command);
}
