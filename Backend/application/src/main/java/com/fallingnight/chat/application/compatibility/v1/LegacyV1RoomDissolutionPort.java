package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1RoomDissolutionPort {
    LegacyV1RoomDissolutionResult dissolve(LegacyV1RoomDissolutionIntent intent);
}
